"""Qt call overlay + floating mini bar for 1:1 audio/video calls."""

from __future__ import annotations

import asyncio
import logging
import sys
import time
from typing import TYPE_CHECKING, Any, Optional

from PyQt6 import QtCore, QtGui, QtMultimedia, QtWidgets

from i2pchat.core.call_adapt import MAX_FRAME_BYTES, AdaptiveController, EffectiveMedia
from i2pchat.core.call_media import (
    FRAME_MS,
    SAMPLE_RATE,
    SAMPLES_PER_FRAME,
    AudioCodec,
    jpeg_from_qimage,
    qimage_from_jpeg,
)
from i2pchat.core.call_prefs import (
    PRESET_IDS,
    PRESET_NORMAL,
    CallPrefs,
    load_call_prefs,
    save_call_prefs,
)
from i2pchat.core.media_framing import (
    TYPE_OPUS,
    TYPE_PCM16,
    TYPE_VIDEO_DELTA,
    TYPE_VIDEO_JPEG,
    TYPE_VIDEO_KEY,
)
from i2pchat.core.video_codec import (
    Vp8Decoder,
    Vp8Encoder,
    qimage_to_rgb24,
    rgb24_to_qimage,
    vp8_available,
)
from i2pchat.gui.popup_geometry import (
    disable_dwm_rounded_frame,
    paint_popup_rounded_bg,
    update_popup_rounded_mask,
)
from i2pchat.presentation.i18n import tr

if TYPE_CHECKING:
    from i2pchat.core.call_session import CallSessionManager

logger = logging.getLogger(__name__)

PCM_FRAMES_PER_PACKET = 3  # 3 x 20 ms PCM per DATAGRAM
PLAY_PREBUFFER_MS = 120
PLAY_MAX_BUFFER_MS = 500

# Радиусы как у главного чата (ChatSurface / menus / buttons): macOS-style chrome.
# Кратно сетке UI: surface 14, content 12, controls 9.
CALL_SURFACE_RADIUS = 14
CALL_PANE_RADIUS = 12
CALL_CONTROL_RADIUS = 9


class CallSettingsDialog(QtWidgets.QDialog):
    def __init__(
        self,
        prefs: CallPrefs,
        parent: Optional[QtWidgets.QWidget] = None,
        *,
        theme_id: str = "night",
    ) -> None:
        # Prefer a top-level window parent: CallOverlay's dark QLabel rules must not
        # cascade into this dialog (invisible labels on the light theme).
        super().__init__(parent)
        self.setObjectName("CallSettingsDialog")
        self.setWindowTitle(tr("call.settings"))
        self._prefs = prefs.clamp()
        form = QtWidgets.QFormLayout(self)
        self._preset = QtWidgets.QComboBox(self)
        self._preset_ids = list(PRESET_IDS)
        for pid in self._preset_ids:
            self._preset.addItem(tr(f"call.preset.{pid}"), pid)
        idx = self._preset_ids.index(self._prefs.preset) if self._prefs.preset in self._preset_ids else 1
        self._preset.setCurrentIndex(idx)
        self._preset.currentIndexChanged.connect(self._on_preset_changed)
        self._audio = QtWidgets.QSpinBox(self)
        self._audio.setRange(12, 64)
        self._audio.setValue(self._prefs.audio_bitrate_kbps)
        self._audio.setSuffix(" kbps")
        self._video = QtWidgets.QSpinBox(self)
        self._video.setRange(50, 800)
        self._video.setValue(self._prefs.video_bitrate_kbps)
        self._video.setSuffix(" kbps")
        self._fps = QtWidgets.QSpinBox(self)
        self._fps.setRange(3, 20)
        self._fps.setValue(self._prefs.video_fps)
        self._edge = QtWidgets.QSpinBox(self)
        self._edge.setRange(160, 720)
        self._edge.setValue(self._prefs.video_max_edge)
        form.addRow(tr("call.preset"), self._preset)
        form.addRow(tr("call.audio_bitrate"), self._audio)
        form.addRow(tr("call.video_bitrate"), self._video)
        form.addRow(tr("call.video_fps"), self._fps)
        form.addRow(tr("call.video_edge"), self._edge)
        buttons = QtWidgets.QDialogButtonBox(
            QtWidgets.QDialogButtonBox.StandardButton.Ok
            | QtWidgets.QDialogButtonBox.StandardButton.Cancel
        )
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        form.addRow(buttons)
        self.apply_theme(theme_id)

    def _on_preset_changed(self, index: int) -> None:
        if index < 0 or index >= len(self._preset_ids):
            return
        tmp = CallPrefs().apply_preset(self._preset_ids[index])
        self._audio.setValue(tmp.audio_bitrate_kbps)
        self._video.setValue(tmp.video_bitrate_kbps)
        self._fps.setValue(tmp.video_fps)
        self._edge.setValue(tmp.video_max_edge)

    def apply_theme(self, theme_id: str) -> None:
        night = (theme_id or "night") == "night"
        if night:
            bg, fg, field, border, btn, btn_fg = (
                "#141417",
                "#f5f5f7",
                "#1f1f23",
                "#3d4450",
                "#2a2d36",
                "#f5f5f7",
            )
        else:
            bg, fg, field, border, btn, btn_fg = (
                "#f5f6f8",
                "#1d1d1f",
                "#ffffff",
                "#d0d5de",
                "#e2e7f0",
                "#1d1d1f",
            )
        self.setStyleSheet(
            f"QDialog#CallSettingsDialog {{"
            f"  background-color: {bg}; color: {fg};"
            f"  border-radius: {CALL_SURFACE_RADIUS}px; }}"
            f"QDialog#CallSettingsDialog QLabel {{ color: {fg}; background: transparent; }}"
            f"QDialog#CallSettingsDialog QSpinBox,"
            f"QDialog#CallSettingsDialog QComboBox {{"
            f"  background: {field}; color: {fg}; border: 1px solid {border};"
            f"  border-radius: {CALL_CONTROL_RADIUS}px; padding: 6px 8px; min-height: 28px; }}"
            f"QDialog#CallSettingsDialog QSpinBox::up-button,"
            f"QDialog#CallSettingsDialog QSpinBox::down-button {{"
            f"  background: {btn}; width: 18px; }}"
            f"QDialog#CallSettingsDialog QComboBox QAbstractItemView {{"
            f"  background: {field}; color: {fg}; selection-background-color: {btn}; }}"
            f"QDialog#CallSettingsDialog QPushButton {{"
            f"  background: {btn}; color: {btn_fg}; border: none;"
            f"  border-radius: {CALL_CONTROL_RADIUS}px; padding: 8px 14px; min-height: 28px; }}"
            f"QDialog#CallSettingsDialog QPushButton:hover {{ background: {border}; }}"
        )

    def result_prefs(self) -> CallPrefs:
        pid = self._preset.currentData()
        self._prefs.preset = str(pid or PRESET_NORMAL)
        self._prefs.audio_bitrate_kbps = self._audio.value()
        self._prefs.video_bitrate_kbps = self._video.value()
        self._prefs.video_fps = self._fps.value()
        self._prefs.video_max_edge = self._edge.value()
        return self._prefs.clamp()


class CallMiniBar(QtWidgets.QFrame):
    """Плавающая панель звонка.

    WA_TranslucentBackground + anti-aliased paintEvent на всех платформах
    (углы прозрачные, фон непрозрачный). Один только QSS на macOS Tool-окне
    часто даёт полностью прозрачный фон. На Windows дополнительно
    NoDropShadowWindowHint и disable_dwm_rounded_frame.
    """

    expand_requested = QtCore.pyqtSignal()
    hangup_requested = QtCore.pyqtSignal()
    toggle_mic = QtCore.pyqtSignal()
    toggle_spk = QtCore.pyqtSignal()
    toggle_cam = QtCore.pyqtSignal()
    toggle_screen = QtCore.pyqtSignal()

    # Синхронно с #ChatSurface / #ActionsPopupSurface (macOS-style chrome).
    _OUTER_RADIUS = float(CALL_SURFACE_RADIUS)

    def __init__(self, parent: Optional[QtWidgets.QWidget] = None) -> None:
        flags = (
            QtCore.Qt.WindowType.Tool
            | QtCore.Qt.WindowType.FramelessWindowHint
            | QtCore.Qt.WindowType.WindowStaysOnTopHint
        )
        if sys.platform.startswith("win"):
            flags |= QtCore.Qt.WindowType.NoDropShadowWindowHint
        super().__init__(parent, flags)
        self.setObjectName("CallMiniBar")
        # Прозрачный HWND: иначе QSS border-radius скругляет только заливку,
        # а углы окна остаются непрозрачными (особенно заметно на Windows).
        self.setAttribute(QtCore.Qt.WidgetAttribute.WA_TranslucentBackground, True)
        self.setAutoFillBackground(False)
        self.setWindowFlag(QtCore.Qt.WindowType.WindowDoesNotAcceptFocus, False)
        # macOS hides Qt::Tool windows whenever the app is not frontmost, so the
        # bar vanished as soon as the user switched apps and looked "lost".
        self.setAttribute(QtCore.Qt.WidgetAttribute.WA_MacAlwaysShowToolWindow, True)
        # Always paint the shell: QSS-only fill on a translucent macOS Tool window
        # often renders fully transparent (labels/buttons float on the desktop).
        self._painted_bg = True
        self._dwm_patched = False
        self._popup_bg = QtGui.QColor(28, 31, 38)
        self._popup_border = QtGui.QColor(58, 63, 75)
        self._theme_id = "night"
        self._drag_offset: Optional[QtCore.QPoint] = None
        self._user_moved = False
        self._phase = "idle"
        self._peer_raw = ""
        self._active_since: Optional[float] = None
        self.setCursor(QtGui.QCursor(QtCore.Qt.CursorShape.OpenHandCursor))
        self.setToolTip(tr("call.mini_drag_hint"))

        lay = QtWidgets.QHBoxLayout(self)
        lay.setContentsMargins(10, 8, 10, 8)
        lay.setSpacing(8)

        grip = QtWidgets.QLabel("⠿", self)
        grip.setObjectName("CallMiniGrip")
        grip.setFixedWidth(14)
        grip.setAlignment(
            QtCore.Qt.AlignmentFlag.AlignVCenter | QtCore.Qt.AlignmentFlag.AlignHCenter
        )
        lay.addWidget(grip)

        text_col = QtWidgets.QVBoxLayout()
        text_col.setContentsMargins(0, 0, 0, 0)
        text_col.setSpacing(0)
        self._status = QtWidgets.QLabel(tr("call.in_call"), self)
        self._status.setObjectName("CallMiniStatus")
        self._peer = QtWidgets.QLabel("", self)
        self._peer.setObjectName("CallMiniPeer")
        self._timer = QtWidgets.QLabel("0:00", self)
        self._timer.setObjectName("CallMiniTimer")
        self._timer.setMinimumWidth(40)
        text_col.addWidget(self._status)
        text_col.addWidget(self._peer)
        lay.addLayout(text_col, 1)
        lay.addWidget(self._timer, 0, QtCore.Qt.AlignmentFlag.AlignVCenter)

        self._mic = QtWidgets.QPushButton(self)
        self._spk = QtWidgets.QPushButton(self)
        self._cam = QtWidgets.QPushButton(self)
        self._screen = QtWidgets.QPushButton(self)
        self._expand = QtWidgets.QPushButton(tr("call.expand"), self)
        self._hangup = QtWidgets.QPushButton("✕", self)
        self._hangup.setObjectName("CallHangupButton")
        self._hangup.setToolTip(tr("call.hangup"))
        self._expand.setToolTip(tr("call.expand"))
        for b, sig in (
            (self._mic, self.toggle_mic),
            (self._spk, self.toggle_spk),
            (self._cam, self.toggle_cam),
            (self._screen, self.toggle_screen),
            (self._expand, self.expand_requested),
            (self._hangup, self.hangup_requested),
        ):
            b.setCursor(QtGui.QCursor(QtCore.Qt.CursorShape.PointingHandCursor))
            b.clicked.connect(sig.emit)
            lay.addWidget(b)

        self._tick = QtCore.QTimer(self)
        self._tick.setInterval(1000)
        self._tick.timeout.connect(self._refresh_timer)
        self.resize(520, 56)
        self.apply_theme("night")

    @staticmethod
    def _short_peer(peer: str) -> str:
        p = (peer or "").strip()
        if not p:
            return tr("call.remote_video")
        if len(p) <= 22:
            return p
        return f"{p[:10]}…{p[-6:]}"

    def _hit_control(self, pos: QtCore.QPoint) -> bool:
        w = self.childAt(pos)
        while w is not None and w is not self:
            if isinstance(w, QtWidgets.QPushButton):
                return True
            w = w.parentWidget()
        return False

    def mousePressEvent(self, event: QtGui.QMouseEvent) -> None:
        if (
            event.button() == QtCore.Qt.MouseButton.LeftButton
            and not self._hit_control(event.position().toPoint())
        ):
            self._drag_offset = (
                event.globalPosition().toPoint() - self.frameGeometry().topLeft()
            )
            self.setCursor(QtGui.QCursor(QtCore.Qt.CursorShape.ClosedHandCursor))
            event.accept()
            return
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event: QtGui.QMouseEvent) -> None:
        if self._drag_offset is not None and event.buttons() & QtCore.Qt.MouseButton.LeftButton:
            self.move(event.globalPosition().toPoint() - self._drag_offset)
            self._user_moved = True
            event.accept()
            return
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event: QtGui.QMouseEvent) -> None:
        if event.button() == QtCore.Qt.MouseButton.LeftButton and self._drag_offset is not None:
            self._drag_offset = None
            self.setCursor(QtGui.QCursor(QtCore.Qt.CursorShape.OpenHandCursor))
            event.accept()
            return
        super().mouseReleaseEvent(event)

    def mouseDoubleClickEvent(self, event: QtGui.QMouseEvent) -> None:
        if (
            event.button() == QtCore.Qt.MouseButton.LeftButton
            and not self._hit_control(event.position().toPoint())
        ):
            self.expand_requested.emit()
            event.accept()
            return
        super().mouseDoubleClickEvent(event)

    def _apply_linux_mask(self) -> None:
        if self._painted_bg and sys.platform.startswith("linux"):
            update_popup_rounded_mask(self, self._OUTER_RADIUS)

    def paintEvent(self, event: QtGui.QPaintEvent) -> None:  # type: ignore[override]
        if self._painted_bg:
            paint_popup_rounded_bg(
                self, self._popup_bg, self._popup_border, self._OUTER_RADIUS
            )
            return
        super().paintEvent(event)

    def resizeEvent(self, event: QtGui.QResizeEvent) -> None:  # type: ignore[override]
        super().resizeEvent(event)
        self._apply_linux_mask()

    def showEvent(self, event: QtGui.QShowEvent) -> None:
        super().showEvent(event)
        if not self._dwm_patched:
            disable_dwm_rounded_frame(self)
            self._dwm_patched = True
        if self._painted_bg and sys.platform.startswith("linux"):
            QtCore.QTimer.singleShot(0, self._apply_linux_mask)
        if not self._tick.isActive():
            self._tick.start()
        self._refresh_timer()

    def hideEvent(self, event: QtGui.QHideEvent) -> None:
        self._tick.stop()
        super().hideEvent(event)

    def _refresh_timer(self) -> None:
        if self._active_since is None:
            self._timer.setText("--:--")
            return
        elapsed = max(0, int(time.monotonic() - self._active_since))
        mm, ss = divmod(elapsed, 60)
        hh, mm = divmod(mm, 60)
        if hh:
            self._timer.setText(f"{hh}:{mm:02d}:{ss:02d}")
        else:
            self._timer.setText(f"{mm}:{ss:02d}")

    def update_flags(
        self,
        *,
        mic: bool,
        spk: bool,
        cam: bool,
        screen: bool,
        peer: str,
        phase: str = "",
        status: str = "",
    ) -> None:
        self._peer_raw = peer or ""
        if phase:
            self._phase = phase
        if self._phase == "active" and self._active_since is None:
            self._active_since = time.monotonic()
        if self._phase in ("idle", "ended"):
            self._active_since = None
        if status:
            self._status.setText(status)
        elif self._phase == "outgoing":
            self._status.setText(tr("call.calling"))
        elif self._phase == "incoming":
            self._status.setText(tr("call.incoming"))
        elif self._phase == "active":
            self._status.setText(tr("call.in_call"))
        else:
            self._status.setText(tr("call.title"))
        self._peer.setText(self._short_peer(self._peer_raw))
        self._peer.setToolTip(self._peer_raw or "")
        self._mic.setText(tr("call.mic_on") if mic else tr("call.mic_off"))
        self._spk.setText(tr("call.spk_on") if spk else tr("call.spk_off"))
        self._cam.setText(tr("call.cam_on") if cam else tr("call.cam_off"))
        self._screen.setText(tr("call.screen_on") if screen else tr("call.screen_off"))
        self._refresh_timer()

    def place_default(self, host_geo: QtCore.QRect) -> None:
        """Position near the host window unless the user already dragged the bar."""
        if self._user_moved:
            self.clamp_to_screen()
            return
        w = max(self.width(), 480)
        x = host_geo.x() + host_geo.width() - w - 24
        y = host_geo.y() + 72
        self.move(max(8, x), max(8, y))
        self.clamp_to_screen()

    def clamp_to_screen(self) -> None:
        """Keep the bar fully reachable (monitor unplugged / window dragged away)."""
        screen = self.screen() or QtGui.QGuiApplication.primaryScreen()
        if screen is None:
            return
        avail = screen.availableGeometry()
        w = max(self.width(), 1)
        h = max(self.height(), 1)
        x = min(max(self.x(), avail.left() + 4), max(avail.left() + 4, avail.right() - w - 4))
        y = min(max(self.y(), avail.top() + 4), max(avail.top() + 4, avail.bottom() - h - 4))
        if (x, y) != (self.x(), self.y()):
            self.move(x, y)

    def apply_theme(self, theme_id: str) -> None:
        """Match ChatWindow ligth/night so the floating bar does not stay dark-only."""
        night = (theme_id or "night") == "night"
        self._theme_id = "night" if night else "ligth"
        if night:
            bg, border, fg, muted, btn, hover = (
                "#1c1f26",
                "#3a3f4b",
                "#e8eaed",
                "#9aa3b2",
                "#2a3140",
                "#363e50",
            )
        else:
            bg, border, fg, muted, btn, hover = (
                "#ffffff",
                "#d0d5de",
                "#1d1d1f",
                "#626875",
                "#e2e7f0",
                "#d5dce8",
            )
        # Shell fill/border via paintEvent; QSS only styles children.
        r_btn = CALL_CONTROL_RADIUS
        self._popup_bg = QtGui.QColor(bg)
        self._popup_border = QtGui.QColor(border)
        self.setStyleSheet(
            f"QFrame#CallMiniBar {{ background: transparent; border: none; color: {fg}; }}"
            f"QFrame#CallMiniBar QLabel {{ color: {fg}; background: transparent; }}"
            f"QFrame#CallMiniBar QLabel#CallMiniGrip {{ color: {muted}; font-size: 14px; }}"
            f"QFrame#CallMiniBar QLabel#CallMiniStatus {{"
            f" color: {fg}; font-weight: 600; font-size: 12px; }}"
            f"QFrame#CallMiniBar QLabel#CallMiniPeer {{"
            f" color: {muted}; font-size: 11px; }}"
            f"QFrame#CallMiniBar QLabel#CallMiniTimer {{"
            f" color: {fg}; font-weight: 600; font-size: 13px; padding: 0 4px; }}"
            f"QFrame#CallMiniBar QPushButton {{"
            f" background: {btn}; color: {fg}; border: none;"
            f" border-radius: {r_btn}px;"
            f" min-height: 30px; padding: 4px 8px; }}"
            f"QFrame#CallMiniBar QPushButton:hover {{ background: {hover}; }}"
            "QFrame#CallMiniBar QPushButton#CallHangupButton {"
            " background: #c62828; color: white; font-weight: bold; min-width: 34px; }"
        )
        self.update()


class CallOverlayWidget(QtWidgets.QWidget):
    """Full call page (shown via ChatWindow's QStackedWidget, not a floating overlay)."""

    closed = QtCore.pyqtSignal()
    minimized = QtCore.pyqtSignal()
    shown = QtCore.pyqtSignal()

    def __init__(self, host: QtWidgets.QWidget) -> None:
        super().__init__(host)
        self._host = host
        self.setObjectName("CallOverlay")
        self.setAttribute(QtCore.Qt.WidgetAttribute.WA_StyledBackground, True)
        # Palette auto-fill paints a sharp rect over stylesheet radii — rely on QSS only.
        self.setAutoFillBackground(False)
        self.setSizePolicy(
            QtWidgets.QSizePolicy.Policy.Expanding,
            QtWidgets.QSizePolicy.Policy.Expanding,
        )
        self._theme_id = "night"
        self.apply_theme("night")

        root = QtWidgets.QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        head_wrap = QtWidgets.QWidget(self)
        head_wrap.setObjectName("CallHeaderBar")
        head_wrap.setAttribute(QtCore.Qt.WidgetAttribute.WA_StyledBackground, True)
        head = QtWidgets.QHBoxLayout(head_wrap)
        head.setContentsMargins(16, 12, 16, 12)
        self._title = QtWidgets.QLabel(tr("call.title"), head_wrap)
        tf = self._title.font()
        tf.setPointSize(tf.pointSize() + 2)
        tf.setBold(True)
        self._title.setFont(tf)
        self._status = QtWidgets.QLabel("", head_wrap)
        self._status.setWordWrap(False)
        self._minimize_btn = QtWidgets.QPushButton(tr("call.minimize"), head_wrap)
        self._settings_btn = QtWidgets.QPushButton(tr("call.settings"), head_wrap)
        self._minimize_btn.clicked.connect(self._on_minimize)
        self._settings_btn.clicked.connect(self._on_settings)
        head.addWidget(self._title, 0)
        head.addWidget(self._status, 1)
        head.addWidget(self._settings_btn, 0)
        head.addWidget(self._minimize_btn, 0)
        root.addWidget(head_wrap, 0)

        videos_wrap = QtWidgets.QWidget(self)
        videos_wrap.setAttribute(QtCore.Qt.WidgetAttribute.WA_StyledBackground, True)
        videos = QtWidgets.QHBoxLayout(videos_wrap)
        videos.setContentsMargins(16, 16, 16, 16)
        videos.setSpacing(12)
        self._remote_view = QtWidgets.QLabel(tr("call.remote_video"), videos_wrap)
        self._local_view = QtWidgets.QLabel(tr("call.local_video"), videos_wrap)
        for lab in (self._remote_view, self._local_view):
            lab.setObjectName("CallVideoPane")
            lab.setAttribute(QtCore.Qt.WidgetAttribute.WA_StyledBackground, True)
            lab.setAlignment(QtCore.Qt.AlignmentFlag.AlignCenter)
            lab.setMinimumSize(200, 160)
            videos.addWidget(lab, 1)
        root.addWidget(videos_wrap, 1)

        controls_wrap = QtWidgets.QWidget(self)
        controls_wrap.setObjectName("CallControlsBar")
        controls_wrap.setAttribute(QtCore.Qt.WidgetAttribute.WA_StyledBackground, True)
        controls = QtWidgets.QHBoxLayout(controls_wrap)
        controls.setContentsMargins(16, 12, 16, 16)
        controls.setSpacing(8)
        self._mic_btn = QtWidgets.QPushButton(tr("call.mic"), controls_wrap)
        self._spk_btn = QtWidgets.QPushButton(tr("call.spk"), controls_wrap)
        self._cam_btn = QtWidgets.QPushButton(tr("call.cam"), controls_wrap)
        self._screen_btn = QtWidgets.QPushButton(tr("call.screen"), controls_wrap)
        self._hangup_btn = QtWidgets.QPushButton(tr("call.hangup"), controls_wrap)
        self._hangup_btn.setObjectName("CallHangupButton")
        self._accept_btn = QtWidgets.QPushButton(tr("call.accept"), controls_wrap)
        self._accept_btn.setObjectName("CallAcceptButton")
        self._reject_btn = QtWidgets.QPushButton(tr("call.reject"), controls_wrap)
        for b in (
            self._mic_btn,
            self._spk_btn,
            self._cam_btn,
            self._screen_btn,
        ):
            controls.addWidget(b, 1)
        controls.addStretch(1)
        controls.addWidget(self._accept_btn, 0)
        controls.addWidget(self._reject_btn, 0)
        controls.addWidget(self._hangup_btn, 0)
        root.addWidget(controls_wrap, 0)

        self._mic_btn.clicked.connect(lambda: self._toggle("mic"))
        self._spk_btn.clicked.connect(lambda: self._toggle("spk"))
        self._cam_btn.clicked.connect(lambda: self._toggle("cam"))
        self._screen_btn.clicked.connect(lambda: self._toggle("screen"))
        self._hangup_btn.clicked.connect(self._hangup)
        self._accept_btn.clicked.connect(self._accept)
        self._reject_btn.clicked.connect(self._reject)

        win = host.window() if host is not None else None
        self._mini = CallMiniBar(win if isinstance(win, QtWidgets.QWidget) else None)
        self._mini.hide()
        self._mini.expand_requested.connect(self._expand_from_mini)
        self._mini.hangup_requested.connect(self._hangup)
        self._mini.toggle_mic.connect(lambda: self._toggle("mic"))
        self._mini.toggle_spk.connect(lambda: self._toggle("spk"))
        self._mini.toggle_cam.connect(lambda: self._toggle("cam"))
        self._mini.toggle_screen.connect(lambda: self._toggle("screen"))

        self._core = None
        self._manager: Optional[Any] = None
        self._codec = AudioCodec()
        self._audio_in: Optional[QtMultimedia.QAudioSource] = None
        self._audio_out: Optional[QtMultimedia.QAudioSink] = None
        self._audio_in_io: Optional[QtCore.QIODevice] = None
        self._audio_out_io: Optional[QtCore.QIODevice] = None
        self._camera: Optional[QtMultimedia.QCamera] = None
        self._capture: Optional[QtMultimedia.QMediaCaptureSession] = None
        self._video_sink: Optional[QtMultimedia.QVideoSink] = None
        self._screen_timer: Optional[QtCore.QTimer] = None
        self._audio_timer = QtCore.QTimer(self)
        self._audio_timer.setInterval(FRAME_MS)
        self._audio_timer.timeout.connect(self._poll_audio)
        self._pcm_buf = bytearray()
        self._pcm_out_acc = bytearray()
        self._play_buf = bytearray()
        self._play_started = False
        self._last_play_data = 0.0
        self._media_epoch = 0
        self._mic_denied = False
        self._cam_denied = False
        self._camera_pending = False
        self._last_video_sent = 0.0
        self._video_inflight = False
        self._status_base = ""
        self._stats_timer = QtCore.QTimer(self)
        self._stats_timer.setInterval(1000)
        self._stats_timer.timeout.connect(self._update_stats)
        self._prefs = load_call_prefs()
        self._want_video_start = False
        self._adapt: Optional[AdaptiveController] = None
        self._effective = EffectiveMedia(
            audio_bitrate_kbps=self._prefs.audio_bitrate_kbps,
            video_bitrate_kbps=self._prefs.video_bitrate_kbps,
            video_fps=self._prefs.video_fps,
            video_max_edge=self._prefs.video_max_edge,
            jpeg_quality=40,
        )
        self._vp8_enc: Optional[Vp8Encoder] = None
        self._vp8_dec: Optional[Vp8Decoder] = None
        self._force_vp8_key = False

    def _ceiling_from_prefs(self) -> EffectiveMedia:
        return EffectiveMedia(
            audio_bitrate_kbps=self._prefs.audio_bitrate_kbps,
            video_bitrate_kbps=self._prefs.video_bitrate_kbps,
            video_fps=self._prefs.video_fps,
            video_max_edge=self._prefs.video_max_edge,
            jpeg_quality=40,
        )

    def _reset_adapt(self) -> None:
        ceil = self._ceiling_from_prefs()
        self._adapt = AdaptiveController(ceil)
        self._effective = self._adapt.effective

    def attach_core(self, core: Any) -> None:
        self._core = core
        if core is not None:
            core.on_call_event = self._on_call_event

    def apply_theme(self, theme_id: str) -> None:
        """Follow ChatWindow ligth/night so the call page matches the rest of the UI."""
        night = (theme_id or "night") == "night"
        self._theme_id = "night" if night else "ligth"
        if night:
            # Близко к #ChatSurface night (rgba(34,37,45) над #101114) + sidebar chrome.
            overlay = "#22252d"
            overlay_border = "#2f3541"
            text = "#e8eaed"
            muted = "#8a93a3"
            pane = "#2a2f3a"
            bar = "#1c1f26"
            btn = "#2a3140"
            btn_hover = "#363e50"
            bar_border = "#2f3541"
        else:
            # Align with THEMES["ligth"] ChatSurface / ComposeBar chrome.
            overlay = "#f2f4f8"
            overlay_border = "#ffffff"
            text = "#1d1d1f"
            muted = "#626875"
            pane = "#e2e7f0"
            bar = "#eaedf4"
            btn = "#f8f9fc"
            btn_hover = "#f0f3f8"
            bar_border = "#d0d5de"
        pal = self.palette()
        pal.setColor(QtGui.QPalette.ColorRole.Window, QtGui.QColor(overlay))
        pal.setColor(QtGui.QPalette.ColorRole.WindowText, QtGui.QColor(text))
        pal.setColor(QtGui.QPalette.ColorRole.ButtonText, QtGui.QColor(text))
        self.setPalette(pal)
        r_surf = CALL_SURFACE_RADIUS
        r_pane = CALL_PANE_RADIUS
        r_btn = CALL_CONTROL_RADIUS
        self.setStyleSheet(
            f"QWidget#CallOverlay {{"
            f"  background-color: {overlay}; color: {text};"
            f"  border: 1px solid {overlay_border};"
            f"  border-radius: {r_surf}px; }}"
            f"QWidget#CallOverlay QLabel {{ color: {text}; background: transparent; }}"
            f"QWidget#CallOverlay QLabel#CallVideoPane {{"
            f"  background-color: {pane}; border-radius: {r_pane}px; color: {muted}; }}"
            f"QWidget#CallOverlay QPushButton {{"
            f"  background-color: {btn}; color: {text}; border: none;"
            f"  border-radius: {r_btn}px; padding: 8px 12px; min-height: 32px; }}"
            f"QWidget#CallOverlay QPushButton:hover {{ background-color: {btn_hover}; }}"
            "QWidget#CallOverlay QPushButton#CallHangupButton {"
            "  background-color: #c62828; color: white; font-weight: bold; }"
            "QWidget#CallOverlay QPushButton#CallAcceptButton {"
            "  background-color: #2e7d32; color: white; }"
            f"QWidget#CallHeaderBar {{"
            f"  background-color: {bar};"
            f"  border-top-left-radius: {r_surf}px;"
            f"  border-top-right-radius: {r_surf}px; }}"
            f"QWidget#CallControlsBar {{"
            f"  background-color: {bar}; border-top: 1px solid {bar_border};"
            f"  border-bottom-left-radius: {r_surf}px;"
            f"  border-bottom-right-radius: {r_surf}px; }}"
        )
        mini = getattr(self, "_mini", None)
        if mini is not None:
            mini.apply_theme(self._theme_id)

    def _set_status(self, text: str) -> None:
        self._status_base = text
        self._status.setText(text)

    def _update_stats(self) -> None:
        """Append live TX/RX counters while a call is active (diagnoses one-way media)."""
        if self._manager is None:
            return
        snap = self._manager.snapshot()
        if snap.get("phase") != "active":
            return
        st = snap.get("stats") or {}
        if self._adapt is not None:
            prev_level = self._effective.level
            self._effective = self._adapt.observe(st)
            if self._effective.level != prev_level:
                logger.info(
                    "call adapt level=%s edge=%s fps=%s vbr=%s",
                    self._effective.level,
                    self._effective.video_max_edge,
                    self._effective.video_fps,
                    self._effective.video_bitrate_kbps,
                )
                if self._effective.level > prev_level:
                    self._force_vp8_key = True
                self._sync_screen_timer_interval()
        tx = int(st.get("tx_packets") or 0)
        rx = int(st.get("rx_packets") or 0)
        base = self._status_base
        if rx > 0 and base == tr("call.media_pending"):
            base = tr("call.connected")
            self._status_base = base
        extra = tr("call.stats", tx=tx, rx=rx)
        if st.get("control_closed"):
            extra += " · " + tr("call.media_error")
        elif self._mic_denied:
            extra += " · " + tr("call.mic_denied")
        self._status.setText(f"{base} · {extra}")

    def _spawn(self, coro: Any) -> None:
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            QtWidgets.QMessageBox.warning(
                self.window(), tr("call.title"), tr("call.no_event_loop")
            )
            return
        loop.create_task(coro)

    def _set_action_buttons_enabled(self, enabled: bool) -> None:
        for b in (
            self._accept_btn,
            self._reject_btn,
            self._hangup_btn,
            self._mic_btn,
            self._spk_btn,
            self._cam_btn,
            self._screen_btn,
        ):
            b.setEnabled(enabled)

    def start_outgoing(self, *, video: bool) -> None:
        if self._core is None:
            return
        # Audio/Video buttons are also the "back to call" affordance when the
        # overlay was minimized to the floating bar (or buried under Status/Chat).
        if self.has_live_call():
            self._manager = self._core.get_call_manager()
            self.show_overlay()
            self._refresh_from_state()
            return
        self._want_video_start = video
        self._prefs = load_call_prefs()
        self._codec = AudioCodec(self._prefs.audio_bitrate_kbps * 1000)
        self.show_overlay()
        self._set_status(tr("call.calling"))
        self._accept_btn.hide()
        self._reject_btn.hide()
        self._hangup_btn.show()
        self._set_action_buttons_enabled(True)
        self._spawn(self._start_outgoing_async(video))

    async def _start_outgoing_async(self, video: bool) -> None:
        try:
            peer = getattr(self._core, "current_peer_addr", None) or ""
            await self._core.start_call(peer, video=video)
            self._manager = self._core.get_call_manager()
            self._refresh_from_state()
        except RuntimeError as exc:
            # Race: call became live between has_live_call() and start_call.
            # Never hide_all() here — that kills the mini-bar while the session
            # stays non-IDLE ("A call is already in progress" forever).
            if "already in progress" in str(exc).lower():
                if self._core is not None:
                    self._manager = self._core.get_call_manager()
                self.show_overlay()
                self._refresh_from_state()
                return
            self.hide_all()
            QtWidgets.QMessageBox.warning(self.window(), tr("call.title"), str(exc))
        except Exception as exc:
            self.hide_all()
            QtWidgets.QMessageBox.warning(self.window(), tr("call.title"), str(exc))

    def show_incoming(self, state: dict[str, Any]) -> None:
        self.show_overlay()
        self._set_status(tr("call.incoming"))
        self._accept_btn.show()
        self._reject_btn.show()
        self._hangup_btn.hide()
        self._set_action_buttons_enabled(True)
        self._title.setText(tr("call.incoming_from", peer=str(state.get("peer", ""))[:24]))
        self._refresh_buttons(state)

    def show_overlay(self) -> None:
        self._mini.hide()
        if self._manager is not None:
            self._manager.set_minimized(False)
        self.shown.emit()

    def shutdown_ui(self) -> None:
        """App exit: stop capture/audio and drop the floating bar without navigation."""
        self._stop_media()
        self._mini.hide()
        self._set_action_buttons_enabled(False)

    def hide_all(self) -> None:
        self._stop_media()
        self._mini.hide()
        self._mini._user_moved = False
        self._mini._active_since = None
        self._mini._phase = "idle"
        self._set_action_buttons_enabled(True)
        self.closed.emit()

    def has_live_call(self) -> bool:
        """True while a call is ringing, dialing or active (overlay must not just vanish)."""
        try:
            mgr = self._manager
            if mgr is None and self._core is not None:
                mgr = self._core.get_call_manager()
            if mgr is None:
                return False
            return str(mgr.snapshot().get("phase") or "idle") in ("outgoing", "incoming", "active")
        except Exception:
            return False

    def _on_minimize(self) -> None:
        self.minimize_to_bar(emit_signal=True)

    def minimize_to_bar(self, *, emit_signal: bool = True) -> None:
        if self._manager is None and self._core is not None:
            try:
                self._manager = self._core.get_call_manager()
            except Exception:
                self._manager = None
        if self._manager is not None:
            self._manager.set_minimized(True)
            snap = self._manager.snapshot()
            phase = str(snap.get("phase") or "")
            if phase == "outgoing":
                status = tr("call.calling")
            elif phase == "incoming":
                status = tr("call.incoming")
            elif snap.get("media_ready"):
                status = tr("call.in_call")
            else:
                status = tr("call.connecting")
            self._mini.update_flags(
                mic=bool(snap.get("mic", True)),
                spk=bool(snap.get("spk", True)),
                cam=bool(snap.get("cam", False)),
                screen=bool(snap.get("screen", False)),
                peer=str(snap.get("peer") or ""),
                phase=phase,
                status=status,
            )
        win = self.window()
        if win is not None:
            self._mini.place_default(win.geometry())
        self._mini.show()
        # Real width is only known after layout on show(); clamp afterwards.
        self._mini.adjustSize()
        self._mini.clamp_to_screen()
        self._mini.raise_()
        if emit_signal:
            self.minimized.emit()

    def _expand_from_mini(self) -> None:
        self._mini.hide()
        self.show_overlay()

    def _on_settings(self) -> None:
        # Parent to the top-level window so CallOverlay QLabel { color: ... } does not
        # paint light-on-light labels inside this dialog.
        host = self.window() if self.window() is not None else self
        dlg = CallSettingsDialog(
            self._prefs,
            host,
            theme_id=getattr(self, "_theme_id", "night"),
        )
        if dlg.exec() != QtWidgets.QDialog.DialogCode.Accepted:
            return
        self._prefs = dlg.result_prefs()
        save_call_prefs(self._prefs)
        self._codec = AudioCodec(self._prefs.audio_bitrate_kbps * 1000)
        self._reset_adapt()
        if self._manager is not None:
            self._manager.prefs = self._prefs
        self._sync_screen_timer_interval()

    def _toggle(self, which: str) -> None:
        if self._core is None:
            return
        self._spawn(self._toggle_async(which))

    async def _toggle_async(self, which: str) -> None:
        mgr = self._core.get_call_manager()
        snap = mgr.snapshot()
        kwargs = {}
        if which == "mic":
            kwargs["mic"] = not snap["mic"]
        elif which == "spk":
            kwargs["spk"] = not snap["spk"]
        elif which == "cam":
            kwargs["cam"] = not snap["cam"]
        elif which == "screen":
            kwargs["screen"] = not snap["screen"]
        await self._core.set_call_media(**kwargs)
        self._refresh_from_state()
        self._sync_capture_devices()

    def _hangup(self) -> None:
        core = self._core
        self.hide_all()
        if core is None:
            return
        self._spawn(self._hangup_async(core))

    async def _hangup_async(self, core: Any) -> None:
        try:
            await core.hangup_call()
        except Exception:
            logger.debug("hangup_call failed", exc_info=True)

    def _accept(self) -> None:
        if self._core is None:
            return
        self._set_status(tr("call.connecting"))
        self._set_action_buttons_enabled(False)
        self._spawn(self._accept_async())

    async def _accept_async(self) -> None:
        try:
            await self._core.accept_call()
            self._manager = self._core.get_call_manager()
            self._accept_btn.hide()
            self._reject_btn.hide()
            self._hangup_btn.show()
            self._set_action_buttons_enabled(True)
            snap = self._manager.snapshot() if self._manager else {}
            if snap.get("media_ready"):
                self._set_status(tr("call.connected"))
            else:
                self._set_status(tr("call.media_pending"))
            self._refresh_from_state()
            self._start_media()
        except Exception as exc:
            self._set_action_buttons_enabled(True)
            QtWidgets.QMessageBox.warning(self.window(), tr("call.title"), str(exc))
            # Call may already be ended; if still incoming, restore buttons.
            phase = ""
            try:
                if self._core is not None:
                    phase = str(self._core.get_call_manager().snapshot().get("phase") or "")
            except Exception:
                phase = ""
            if phase == "incoming":
                self._accept_btn.show()
                self._reject_btn.show()
                self._hangup_btn.hide()
                self._set_status(tr("call.incoming"))
            else:
                self.hide_all()

    def _reject(self) -> None:
        core = self._core
        self.hide_all()
        if core is None:
            return
        self._spawn(self._reject_async(core))

    async def _reject_async(self, core: Any) -> None:
        try:
            await core.reject_call()
        except Exception:
            logger.debug("reject_call failed", exc_info=True)

    def _on_call_event(self, kind: str, payload: dict[str, Any]) -> None:
        QtCore.QTimer.singleShot(0, lambda: self._handle_event(kind, payload))

    def _handle_event(self, kind: str, payload: dict[str, Any]) -> None:
        state = payload.get("state") or {}
        if kind == "incoming":
            self._manager = self._core.get_call_manager() if self._core else None
            self.show_incoming(state)
            return
        if kind == "active":
            self._accept_btn.hide()
            self._reject_btn.hide()
            self._hangup_btn.show()
            self._set_action_buttons_enabled(True)
            if state.get("media_ready"):
                self._set_status(tr("call.connected"))
            else:
                self._set_status(tr("call.media_pending"))
            self._refresh_buttons(state)
            self._start_media()
            return
        if kind == "media_pending":
            self._set_status(tr("call.media_pending"))
            return
        if kind in ("media_ready", "media_flowing"):
            if self._status_base == tr("call.media_pending"):
                self._set_status(tr("call.connected"))
            return
        if kind == "media_error":
            self._update_stats()
            return
        if kind == "ended":
            self.hide_all()
            return
        if kind == "media_packet":
            self._on_media_packet(payload)
            return
        if kind in ("media", "remote_media", "outgoing", "minimized"):
            self._refresh_buttons(state)
            if kind == "outgoing":
                self._set_status(tr("call.calling"))
                self._hangup_btn.show()
                self._accept_btn.hide()
                self._reject_btn.hide()
                self._set_action_buttons_enabled(True)
            if kind == "media":
                self._sync_capture_devices()

    def _refresh_from_state(self) -> None:
        if self._manager is None and self._core is not None:
            self._manager = self._core.get_call_manager()
        if self._manager is None:
            return
        self._refresh_buttons(self._manager.snapshot())

    def _refresh_buttons(self, state: dict[str, Any]) -> None:
        mic = bool(state.get("mic", True))
        spk = bool(state.get("spk", True))
        cam = bool(state.get("cam", False))
        screen = bool(state.get("screen", False))
        self._mic_btn.setText(tr("call.mic_on") if mic else tr("call.mic_off"))
        self._spk_btn.setText(tr("call.spk_on") if spk else tr("call.spk_off"))
        self._cam_btn.setText(tr("call.cam_on") if cam else tr("call.cam_off"))
        self._screen_btn.setText(tr("call.screen_on") if screen else tr("call.screen_off"))
        peer = str(state.get("peer") or "")
        phase = str(state.get("phase") or "")
        if phase == "outgoing":
            self._set_status(tr("call.calling"))
            mini_status = tr("call.calling")
        elif phase == "incoming":
            mini_status = tr("call.incoming")
        elif phase == "active":
            mini_status = (
                tr("call.in_call") if state.get("media_ready") else tr("call.connecting")
            )
        else:
            mini_status = tr("call.title")
        self._mini.update_flags(
            mic=mic,
            spk=spk,
            cam=cam,
            screen=screen,
            peer=peer,
            phase=phase,
            status=mini_status,
        )

    @staticmethod
    def _request_permission(perm: Any, callback: Any) -> None:
        """Ask Qt (macOS TCC / Android-style permission API) and call ``callback(bool)``.

        PyQt6 only exposes ``requestPermission(permission, handler)`` — the Qt C++
        ``(permission, context, functor)`` overload is not wrapped. Passing a
        QObject as the second argument raises TypeError; the old code caught that
        and falsely assumed access was granted, so macOS never showed the camera
        prompt and ``QCamera.start()`` failed silently.
        """
        app = QtCore.QCoreApplication.instance()
        if app is None:
            callback(False)
            return
        granted = QtCore.Qt.PermissionStatus.Granted
        denied = QtCore.Qt.PermissionStatus.Denied
        undetermined = QtCore.Qt.PermissionStatus.Undetermined
        try:
            status = app.checkPermission(perm)
        except Exception:
            # No permission API (older Qt / plain desktop): open the device.
            logger.debug("checkPermission unavailable; assuming granted", exc_info=True)
            callback(True)
            return
        if status == granted:
            callback(True)
            return
        if status == denied:
            callback(False)
            return
        if status != undetermined:
            callback(False)
            return
        try:
            # Handler arg may be a generic QPermission; always re-check with the
            # typed permission object we requested.
            def _on_result(_result: Any = None) -> None:
                try:
                    st = app.checkPermission(perm)
                except Exception:
                    callback(True)
                    return
                if st == denied:
                    callback(False)
                    return
                # Granted — or still Undetermined (common on Linux/X11 and some
                # portals that never flip the flag). Only hard-deny on Denied so
                # PipeWire/V4L2 capture can proceed.
                callback(True)

            app.requestPermission(perm, _on_result)
        except Exception:
            # requestPermission missing/broken: do not block Linux camera/mic.
            logger.debug("requestPermission failed; assuming granted", exc_info=True)
            callback(True)

    def _start_media(self) -> None:
        self._stop_media()
        self._media_epoch += 1
        epoch = self._media_epoch
        self._mic_denied = False
        self._cam_denied = False
        self._prefs = load_call_prefs()
        self._codec = AudioCodec(self._prefs.audio_bitrate_kbps * 1000)
        self._reset_adapt()
        self._start_audio_output()
        self._audio_timer.start()
        self._stats_timer.start()
        self._request_permission(
            QtCore.QMicrophonePermission(),
            lambda ok, e=epoch: self._on_mic_permission(ok, e),
        )
        # Camera TCC prompt runs from _ensure_camera when local_cam is on. This only
        # registers under Privacy → Camera when Contents/MacOS/I2PChat is a Mach-O
        # (see build-macos.sh — shell wrappers in MacOS/ break TCC attribution).
        self._sync_capture_devices()

    def _audio_format(self) -> QtMultimedia.QAudioFormat:
        fmt = QtMultimedia.QAudioFormat()
        fmt.setSampleRate(SAMPLE_RATE)
        fmt.setChannelCount(1)
        fmt.setSampleFormat(QtMultimedia.QAudioFormat.SampleFormat.Int16)
        return fmt

    def _start_audio_output(self) -> None:
        try:
            out_dev = QtMultimedia.QMediaDevices.defaultAudioOutput()
            if out_dev.isNull():
                logger.warning("call audio: no default output device")
                return
            self._audio_out = QtMultimedia.QAudioSink(out_dev, self._audio_format(), self)
            self._audio_out_io = self._audio_out.start()
            logger.info("call audio: output %s", out_dev.description())
        except Exception:
            logger.exception("audio output failed")

    def _on_mic_permission(self, ok: bool, epoch: int) -> None:
        if epoch != self._media_epoch:
            return
        if not ok:
            self._mic_denied = True
            logger.warning("call audio: microphone permission denied")
            return
        try:
            in_dev = QtMultimedia.QMediaDevices.defaultAudioInput()
            if in_dev.isNull():
                logger.warning("call audio: no default input device")
                self._mic_denied = True
                return
            self._audio_in = QtMultimedia.QAudioSource(in_dev, self._audio_format(), self)
            self._audio_in.stateChanged.connect(
                lambda st: logger.info("call audio: input state %s", st)
            )
            self._audio_in_io = self._audio_in.start()
            logger.info("call audio: input %s", in_dev.description())
        except Exception:
            logger.exception("audio input failed")

    def _sync_capture_devices(self) -> None:
        if self._manager is None:
            return
        snap = self._manager.snapshot()
        want_cam = bool(snap.get("cam"))
        want_screen = bool(snap.get("screen"))
        if want_cam and not want_screen:
            self._ensure_camera()
        else:
            self._stop_camera()
        if want_screen:
            self._ensure_screen()
        else:
            self._stop_screen()

    def _ensure_camera(self) -> None:
        if self._camera is not None or self._camera_pending:
            return
        # On macOS, videoInputs() can be empty until Camera TCC is granted — always
        # request permission first, then enumerate devices in the callback.
        self._camera_pending = True
        epoch = self._media_epoch
        self._request_permission(
            QtCore.QCameraPermission(),
            lambda ok, e=epoch: self._on_camera_permission(ok, e),
        )

    def _pick_camera_device(self) -> Any:
        devices = QtMultimedia.QMediaDevices.videoInputs()
        default = QtMultimedia.QMediaDevices.defaultVideoInput()
        if not default.isNull():
            return default
        return devices[0] if devices else None

    def _on_camera_permission(self, ok: bool, epoch: int) -> None:
        self._camera_pending = False
        if epoch != self._media_epoch or self._camera is not None:
            return
        if not ok:
            self._cam_denied = True
            logger.warning("call video: camera permission denied")
            self._set_status(tr("call.cam_denied"))
            return
        if self._manager is None or not self._manager.snapshot().get("cam"):
            return
        device = self._pick_camera_device()
        if device is None:
            logger.warning("call video: no camera found after permission grant")
            self._set_status(tr("call.cam_denied"))
            return
        self._camera = QtMultimedia.QCamera(device, self)
        try:
            self._camera.errorOccurred.connect(self._on_camera_error)
        except Exception:
            pass
        self._capture = QtMultimedia.QMediaCaptureSession(self)
        self._video_sink = QtMultimedia.QVideoSink(self)
        self._capture.setCamera(self._camera)
        self._capture.setVideoSink(self._video_sink)
        self._video_sink.videoFrameChanged.connect(self._on_local_frame)
        try:
            self._camera.start()
            logger.info("call video: camera started (%s)", device.description())
        except Exception:
            logger.exception("camera start failed")
            self._set_status(tr("call.cam_denied"))

    def _on_camera_error(self, error: Any, error_string: str = "") -> None:
        logger.warning("call video: camera error %s (%s)", error, error_string)
        if error_string:
            self._set_status(tr("call.cam_denied"))

    def _stop_camera(self) -> None:
        if self._camera is not None:
            try:
                self._camera.stop()
            except Exception:
                pass
        self._camera = None
        self._capture = None
        self._video_sink = None

    def _ensure_screen(self) -> None:
        if self._screen_timer is not None:
            return
        self._screen_timer = QtCore.QTimer(self)
        self._screen_timer.timeout.connect(self._capture_screen_frame)
        self._sync_screen_timer_interval()
        self._screen_timer.start()

    def _sync_screen_timer_interval(self) -> None:
        if self._screen_timer is None:
            return
        fps = max(3, min(20, self._effective.video_fps))
        self._screen_timer.setInterval(max(50, int(1000 / fps)))

    def _stop_screen(self) -> None:
        if self._screen_timer is not None:
            self._screen_timer.stop()
            self._screen_timer = None

    def _close_vp8(self) -> None:
        if self._vp8_enc is not None:
            try:
                self._vp8_enc.close()
            except Exception:
                pass
        if self._vp8_dec is not None:
            try:
                self._vp8_dec.close()
            except Exception:
                pass
        self._vp8_enc = None
        self._vp8_dec = None

    def _stop_media(self) -> None:
        self._media_epoch += 1
        self._camera_pending = False
        self._audio_timer.stop()
        self._stats_timer.stop()
        self._stop_camera()
        self._stop_screen()
        self._close_vp8()
        if self._audio_in is not None:
            try:
                self._audio_in.stop()
            except Exception:
                pass
        if self._audio_out is not None:
            try:
                self._audio_out.stop()
            except Exception:
                pass
        self._audio_in = None
        self._audio_out = None
        self._audio_in_io = None
        self._audio_out_io = None
        self._pcm_buf.clear()
        self._pcm_out_acc.clear()
        self._play_buf.clear()
        self._play_started = False
        self._video_inflight = False

    def _poll_audio(self) -> None:
        self._pump_playback()
        if self._manager is None or self._audio_in_io is None:
            return
        snap = self._manager.snapshot()
        if snap.get("phase") != "active":
            return
        data = bytes(self._audio_in_io.readAll())
        if not snap.get("mic"):
            self._pcm_buf.clear()
            self._pcm_out_acc.clear()
            return
        if data:
            self._pcm_buf.extend(data)
        frame_bytes = SAMPLES_PER_FRAME * 2
        while len(self._pcm_buf) >= frame_bytes:
            chunk = bytes(self._pcm_buf[:frame_bytes])
            del self._pcm_buf[:frame_bytes]
            encoded, is_opus = self._codec.encode(chunk)
            if is_opus:
                self._spawn(self._manager.send_audio_frame(encoded, opus=True))
                continue
            # PCM fallback: pack several 20 ms frames per datagram (less I2P overhead).
            self._pcm_out_acc.extend(encoded)
            if len(self._pcm_out_acc) >= frame_bytes * PCM_FRAMES_PER_PACKET:
                packed = bytes(self._pcm_out_acc)
                self._pcm_out_acc.clear()
                self._spawn(self._manager.send_audio_frame(packed, opus=False))

    def _enqueue_playback(self, pcm: bytes) -> None:
        """Jitter buffer: queue decoded PCM, cap latency by dropping the oldest audio."""
        self._play_buf.extend(pcm)
        self._last_play_data = time.monotonic()
        max_bytes = SAMPLE_RATE * 2 * PLAY_MAX_BUFFER_MS // 1000
        overflow = len(self._play_buf) - max_bytes
        if overflow > 0:
            overflow -= overflow % 2
            del self._play_buf[:overflow]

    def _pump_playback(self) -> None:
        sink = self._audio_out
        io = self._audio_out_io
        if sink is None or io is None:
            return
        if not self._play_started:
            if len(self._play_buf) < SAMPLE_RATE * 2 * PLAY_PREBUFFER_MS // 1000:
                return
            self._play_started = True
        if not self._play_buf:
            # Starved for a while: re-prebuffer so playback does not stutter.
            if time.monotonic() - self._last_play_data > 0.3:
                self._play_started = False
            return
        n = min(int(sink.bytesFree()), len(self._play_buf))
        n -= n % 2
        if n <= 0:
            return
        io.write(bytes(self._play_buf[:n]))
        del self._play_buf[:n]

    def _send_jpeg_task(self, jpeg: bytes) -> None:
        mgr = self._manager
        if mgr is None or self._video_inflight:
            return
        self._video_inflight = True
        self._last_video_sent = time.monotonic()

        async def _run() -> None:
            try:
                await mgr.send_jpeg_frame(jpeg)
            finally:
                self._video_inflight = False

        self._spawn(_run())

    def _send_vp8_task(self, payload: bytes, *, keyframe: bool) -> None:
        mgr = self._manager
        if mgr is None or self._video_inflight:
            return
        self._video_inflight = True
        self._last_video_sent = time.monotonic()

        async def _run() -> None:
            try:
                await mgr.send_vp8_frame(payload, keyframe=keyframe)
            finally:
                self._video_inflight = False

        self._spawn(_run())

    def _show_local_preview(self, img: QtGui.QImage) -> None:
        pix = QtGui.QPixmap.fromImage(
            img.scaled(
                self._local_view.size(),
                QtCore.Qt.AspectRatioMode.KeepAspectRatio,
                QtCore.Qt.TransformationMode.SmoothTransformation,
            )
        )
        self._local_view.setPixmap(pix)

    def _encode_and_send_video(self, img: QtGui.QImage, *, screen: bool = False) -> None:
        if self._manager is None or img.isNull():
            return
        eff = self._effective
        min_interval = 1.0 / max(1, eff.video_fps)
        if self._video_inflight or time.monotonic() - self._last_video_sent < min_interval:
            return
        self._show_local_preview(img)
        use_vp8 = bool(getattr(self._manager, "uses_vp8", lambda: False)())
        if use_vp8 and vp8_available():
            # Scale to even edge for VP8.
            edge = eff.video_max_edge
            w, h = img.width(), img.height()
            m = max(w, h)
            if m > edge > 0:
                scale = edge / float(m)
                img = img.scaled(
                    max(16, int(w * scale) & ~1),
                    max(16, int(h * scale) & ~1),
                    QtCore.Qt.AspectRatioMode.KeepAspectRatio,
                    QtCore.Qt.TransformationMode.SmoothTransformation,
                )
            rgb, rw, rh = qimage_to_rgb24(img)
            try:
                if self._vp8_enc is None:
                    self._vp8_enc = Vp8Encoder(
                        width=rw,
                        height=rh,
                        fps=eff.video_fps,
                        bitrate_kbps=eff.video_bitrate_kbps,
                        keyframe_interval_s=2.0,
                    )
                else:
                    # No-op unless size/fps/bitrate actually changed. Recreating
                    # the codec every frame made each picture a huge keyframe.
                    self._vp8_enc.reconfigure(
                        width=rw,
                        height=rh,
                        fps=eff.video_fps,
                        bitrate_kbps=eff.video_bitrate_kbps,
                    )
                payload, is_key = self._vp8_enc.encode_rgb24(
                    rgb, width=rw, height=rh, force_key=self._force_vp8_key
                )
                self._force_vp8_key = False
                if payload and len(payload) > MAX_FRAME_BYTES:
                    # More than ~4 DATAGRAM fragments almost never reassembles.
                    logger.info(
                        "drop oversized vp8 frame (%d bytes > %d)",
                        len(payload),
                        MAX_FRAME_BYTES,
                    )
                    self._force_vp8_key = True
                    return
                if payload:
                    self._send_vp8_task(payload, keyframe=is_key)
                    return
            except Exception:
                logger.debug("vp8 encode failed; falling back to JPEG", exc_info=True)
                self._close_vp8()
        quality = 35 if screen else eff.jpeg_quality
        jpeg = jpeg_from_qimage(
            img,
            max_edge=eff.video_max_edge,
            quality=quality,
            target_bytes=eff.target_frame_bytes(),
        )
        if jpeg:
            self._send_jpeg_task(jpeg)

    def _on_local_frame(self, frame: QtMultimedia.QVideoFrame) -> None:
        if self._manager is None:
            return
        img = frame.toImage()
        if img.isNull():
            return
        self._encode_and_send_video(img, screen=False)

    def _capture_screen_frame(self) -> None:
        if self._manager is None:
            return
        screen = QtWidgets.QApplication.primaryScreen()
        if screen is None:
            return
        pix = screen.grabWindow(0)
        self._encode_and_send_video(pix.toImage(), screen=True)

    def _show_remote_image(self, img: QtGui.QImage) -> None:
        if img is None or img.isNull():
            return
        self._remote_view.setPixmap(
            QtGui.QPixmap.fromImage(img).scaled(
                self._remote_view.size(),
                QtCore.Qt.AspectRatioMode.KeepAspectRatio,
                QtCore.Qt.TransformationMode.SmoothTransformation,
            )
        )

    def _on_media_packet(self, payload: dict[str, Any]) -> None:
        if self._manager is None:
            return
        snap = self._manager.snapshot()
        ptype = int(payload.get("ptype") or 0)
        data = payload.get("payload") or b""
        if ptype in (TYPE_OPUS, TYPE_PCM16):
            if not snap.get("spk") or self._audio_out_io is None:
                return
            pcm = self._codec.decode(data, opus=(ptype == TYPE_OPUS))
            if pcm:
                self._enqueue_playback(pcm)
        elif ptype == TYPE_VIDEO_JPEG:
            img = qimage_from_jpeg(data)
            if img is not None:
                self._show_remote_image(img)
        elif ptype in (TYPE_VIDEO_KEY, TYPE_VIDEO_DELTA):
            try:
                if self._vp8_dec is None:
                    self._vp8_dec = Vp8Decoder()
                decoded = self._vp8_dec.decode(data)
            except Exception:
                logger.debug("vp8 remote decode failed", exc_info=True)
                self._force_vp8_key = True
                return
            if not decoded:
                if ptype == TYPE_VIDEO_DELTA:
                    self._force_vp8_key = True
                return
            rgb, w, h = decoded
            img = rgb24_to_qimage(rgb, w, h)
            if img is not None:
                self._show_remote_image(img)
