"""Qt call overlay + floating mini bar for 1:1 audio/video calls."""

from __future__ import annotations

import asyncio
import logging
from typing import TYPE_CHECKING, Any, Optional

from PyQt6 import QtCore, QtGui, QtMultimedia, QtWidgets

from i2pchat.core.call_media import (
    FRAME_MS,
    SAMPLE_RATE,
    SAMPLES_PER_FRAME,
    AudioCodec,
    jpeg_from_qimage,
    qimage_from_jpeg,
)
from i2pchat.core.call_prefs import CallPrefs, load_call_prefs, save_call_prefs
from i2pchat.core.media_framing import TYPE_OPUS, TYPE_PCM16, TYPE_VIDEO_JPEG
from i2pchat.presentation.i18n import tr

if TYPE_CHECKING:
    from i2pchat.core.call_session import CallSessionManager

logger = logging.getLogger(__name__)


class CallSettingsDialog(QtWidgets.QDialog):
    def __init__(self, prefs: CallPrefs, parent: Optional[QtWidgets.QWidget] = None) -> None:
        super().__init__(parent)
        self.setWindowTitle(tr("call.settings"))
        self._prefs = prefs.clamp()
        form = QtWidgets.QFormLayout(self)
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

    def result_prefs(self) -> CallPrefs:
        self._prefs.audio_bitrate_kbps = self._audio.value()
        self._prefs.video_bitrate_kbps = self._video.value()
        self._prefs.video_fps = self._fps.value()
        self._prefs.video_max_edge = self._edge.value()
        return self._prefs.clamp()


class CallMiniBar(QtWidgets.QFrame):
    expand_requested = QtCore.pyqtSignal()
    hangup_requested = QtCore.pyqtSignal()
    toggle_mic = QtCore.pyqtSignal()
    toggle_spk = QtCore.pyqtSignal()
    toggle_cam = QtCore.pyqtSignal()
    toggle_screen = QtCore.pyqtSignal()

    def __init__(self, parent: Optional[QtWidgets.QWidget] = None) -> None:
        super().__init__(parent, QtCore.Qt.WindowType.Tool | QtCore.Qt.WindowType.FramelessWindowHint)
        self.setObjectName("CallMiniBar")
        self.setStyleSheet(
            "QFrame#CallMiniBar { background: #1c1f26; border: 1px solid #3a3f4b; border-radius: 10px; }"
            "QPushButton { min-width: 36px; min-height: 28px; }"
        )
        lay = QtWidgets.QHBoxLayout(self)
        lay.setContentsMargins(8, 6, 8, 6)
        self._title = QtWidgets.QLabel(tr("call.in_call"), self)
        self._mic = QtWidgets.QPushButton("Mic", self)
        self._spk = QtWidgets.QPushButton("Spk", self)
        self._cam = QtWidgets.QPushButton("Cam", self)
        self._screen = QtWidgets.QPushButton("Scr", self)
        self._expand = QtWidgets.QPushButton("□", self)
        self._hangup = QtWidgets.QPushButton("✕", self)
        self._hangup.setStyleSheet("background:#c62828; color:white;")
        for b, sig in (
            (self._mic, self.toggle_mic),
            (self._spk, self.toggle_spk),
            (self._cam, self.toggle_cam),
            (self._screen, self.toggle_screen),
            (self._expand, self.expand_requested),
            (self._hangup, self.hangup_requested),
        ):
            b.clicked.connect(sig.emit)
            lay.addWidget(b if b is not self._title else self._title)
        lay.insertWidget(0, self._title)
        self.resize(420, 44)

    def update_flags(self, *, mic: bool, spk: bool, cam: bool, screen: bool, peer: str) -> None:
        self._title.setText((peer or tr("call.in_call"))[:28])
        self._mic.setText("Mic✓" if mic else "Mic")
        self._spk.setText("Spk✓" if spk else "Spk")
        self._cam.setText("Cam✓" if cam else "Cam")
        self._screen.setText("Scr✓" if screen else "Scr")


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
        self.setAutoFillBackground(True)
        self.setSizePolicy(
            QtWidgets.QSizePolicy.Policy.Expanding,
            QtWidgets.QSizePolicy.Policy.Expanding,
        )
        pal = self.palette()
        pal.setColor(QtGui.QPalette.ColorRole.Window, QtGui.QColor("#10141c"))
        pal.setColor(QtGui.QPalette.ColorRole.WindowText, QtGui.QColor("#e8eaed"))
        pal.setColor(QtGui.QPalette.ColorRole.ButtonText, QtGui.QColor("#e8eaed"))
        self.setPalette(pal)
        self.setStyleSheet(
            "QWidget#CallOverlay { background-color: #10141c; color: #e8eaed; }"
            "QWidget#CallOverlay QLabel { color: #e8eaed; background: transparent; }"
            "QWidget#CallOverlay QPushButton {"
            "  background-color: #2a3140; color: #e8eaed; border: none;"
            "  border-radius: 8px; padding: 8px 12px; min-height: 32px; }"
            "QWidget#CallOverlay QPushButton:hover { background-color: #363e50; }"
            "QWidget#CallOverlay QPushButton#CallHangupButton {"
            "  background-color: #c62828; color: white; font-weight: bold; }"
            "QWidget#CallOverlay QPushButton#CallAcceptButton {"
            "  background-color: #2e7d32; color: white; }"
            "QWidget#CallControlsBar { background-color: #161b26; border-top: 1px solid #2a3140; }"
        )

        root = QtWidgets.QVBoxLayout(self)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        head_wrap = QtWidgets.QWidget(self)
        head_wrap.setObjectName("CallHeaderBar")
        head_wrap.setStyleSheet("QWidget#CallHeaderBar { background-color: #161b26; }")
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
        videos_wrap.setStyleSheet("background-color: #10141c;")
        videos = QtWidgets.QHBoxLayout(videos_wrap)
        videos.setContentsMargins(16, 16, 16, 16)
        videos.setSpacing(12)
        self._remote_view = QtWidgets.QLabel(tr("call.remote_video"), videos_wrap)
        self._local_view = QtWidgets.QLabel(tr("call.local_video"), videos_wrap)
        for lab in (self._remote_view, self._local_view):
            lab.setAlignment(QtCore.Qt.AlignmentFlag.AlignCenter)
            lab.setMinimumSize(200, 160)
            lab.setStyleSheet(
                "background-color:#1a2030; border-radius:8px; color:#8a93a3;"
            )
            videos.addWidget(lab, 1)
        root.addWidget(videos_wrap, 1)

        controls_wrap = QtWidgets.QWidget(self)
        controls_wrap.setObjectName("CallControlsBar")
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
        self._prefs = load_call_prefs()
        self._want_video_start = False

    def attach_core(self, core: Any) -> None:
        self._core = core
        if core is not None:
            core.on_call_event = self._on_call_event

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
        self._want_video_start = video
        self._prefs = load_call_prefs()
        self._codec = AudioCodec(self._prefs.audio_bitrate_kbps * 1000)
        self.show_overlay()
        self._status.setText(tr("call.calling"))
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
        except Exception as exc:
            self.hide_all()
            QtWidgets.QMessageBox.warning(self.window(), tr("call.title"), str(exc))

    def show_incoming(self, state: dict[str, Any]) -> None:
        self.show_overlay()
        self._status.setText(tr("call.incoming"))
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

    def hide_all(self) -> None:
        self._stop_media()
        self._mini.hide()
        self._set_action_buttons_enabled(True)
        self.closed.emit()

    def _on_minimize(self) -> None:
        if self._manager is not None:
            self._manager.set_minimized(True)
            snap = self._manager.snapshot()
            self._mini.update_flags(
                mic=snap["mic"],
                spk=snap["spk"],
                cam=snap["cam"],
                screen=snap["screen"],
                peer=str(snap.get("peer") or ""),
            )
        win = self.window()
        if win is not None:
            geo = win.geometry()
            self._mini.move(geo.x() + geo.width() - 440, geo.y() + 80)
        self._mini.show()
        self._mini.raise_()
        self.minimized.emit()

    def _expand_from_mini(self) -> None:
        self._mini.hide()
        self.show_overlay()

    def _on_settings(self) -> None:
        dlg = CallSettingsDialog(self._prefs, self)
        if dlg.exec() != QtWidgets.QDialog.DialogCode.Accepted:
            return
        self._prefs = dlg.result_prefs()
        save_call_prefs(self._prefs)
        self._codec = AudioCodec(self._prefs.audio_bitrate_kbps * 1000)
        if self._manager is not None:
            self._manager.prefs = self._prefs

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
        self._status.setText(tr("call.connecting"))
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
                self._status.setText(tr("call.connected"))
            else:
                self._status.setText(tr("call.media_pending"))
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
                self._status.setText(tr("call.incoming"))
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
                self._status.setText(tr("call.connected"))
            else:
                self._status.setText(tr("call.media_pending"))
            self._refresh_buttons(state)
            self._start_media()
            return
        if kind == "media_pending":
            self._status.setText(tr("call.media_pending"))
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
                self._status.setText(tr("call.calling"))
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
        if state.get("phase") == "outgoing":
            self._status.setText(tr("call.calling"))
        self._mini.update_flags(mic=mic, spk=spk, cam=cam, screen=screen, peer=peer)

    def _start_media(self) -> None:
        self._stop_media()
        fmt = QtMultimedia.QAudioFormat()
        fmt.setSampleRate(SAMPLE_RATE)
        fmt.setChannelCount(1)
        fmt.setSampleFormat(QtMultimedia.QAudioFormat.SampleFormat.Int16)
        try:
            in_dev = QtMultimedia.QMediaDevices.defaultAudioInput()
            out_dev = QtMultimedia.QMediaDevices.defaultAudioOutput()
            self._audio_in = QtMultimedia.QAudioSource(in_dev, fmt, self)
            self._audio_out = QtMultimedia.QAudioSink(out_dev, fmt, self)
            self._audio_in_io = self._audio_in.start()
            self._audio_out_io = self._audio_out.start()
            self._audio_timer.start()
        except Exception:
            logger.exception("audio devices failed")
        self._sync_capture_devices()

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
        if self._camera is not None:
            return
        cameras = QtMultimedia.QMediaDevices.videoInputs()
        if not cameras:
            return
        self._camera = QtMultimedia.QCamera(cameras[0], self)
        self._capture = QtMultimedia.QMediaCaptureSession(self)
        self._video_sink = QtMultimedia.QVideoSink(self)
        self._capture.setCamera(self._camera)
        self._capture.setVideoSink(self._video_sink)
        self._video_sink.videoFrameChanged.connect(self._on_local_frame)
        try:
            self._camera.start()
        except Exception:
            logger.exception("camera start failed")

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
        fps = max(3, min(20, self._prefs.video_fps))
        self._screen_timer.setInterval(max(50, int(1000 / fps)))
        self._screen_timer.timeout.connect(self._capture_screen_frame)
        self._screen_timer.start()

    def _stop_screen(self) -> None:
        if self._screen_timer is not None:
            self._screen_timer.stop()
            self._screen_timer = None

    def _stop_media(self) -> None:
        self._audio_timer.stop()
        self._stop_camera()
        self._stop_screen()
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

    def _poll_audio(self) -> None:
        if self._manager is None or self._audio_in_io is None:
            return
        snap = self._manager.snapshot()
        if snap.get("phase") != "active":
            return
        data = bytes(self._audio_in_io.readAll())
        if not snap.get("mic"):
            return
        if data:
            self._pcm_buf.extend(data)
        frame_bytes = SAMPLES_PER_FRAME * 2
        while len(self._pcm_buf) >= frame_bytes:
            chunk = bytes(self._pcm_buf[:frame_bytes])
            del self._pcm_buf[:frame_bytes]
            encoded, is_opus = self._codec.encode(chunk)
            self._spawn(self._manager.send_audio_frame(encoded, opus=is_opus))

    def _on_local_frame(self, frame: QtMultimedia.QVideoFrame) -> None:
        if self._manager is None:
            return
        img = frame.toImage()
        if img.isNull():
            return
        pix = QtGui.QPixmap.fromImage(img.scaled(
            self._local_view.size(),
            QtCore.Qt.AspectRatioMode.KeepAspectRatio,
            QtCore.Qt.TransformationMode.SmoothTransformation,
        ))
        self._local_view.setPixmap(pix)
        jpeg = jpeg_from_qimage(img, max_edge=self._prefs.video_max_edge)
        if jpeg:
            self._spawn(self._manager.send_jpeg_frame(jpeg))

    def _capture_screen_frame(self) -> None:
        if self._manager is None:
            return
        screen = QtWidgets.QApplication.primaryScreen()
        if screen is None:
            return
        pix = screen.grabWindow(0)
        img = pix.toImage()
        self._local_view.setPixmap(
            pix.scaled(
                self._local_view.size(),
                QtCore.Qt.AspectRatioMode.KeepAspectRatio,
                QtCore.Qt.TransformationMode.SmoothTransformation,
            )
        )
        jpeg = jpeg_from_qimage(img, max_edge=self._prefs.video_max_edge, quality=45)
        if jpeg:
            self._spawn(self._manager.send_jpeg_frame(jpeg))

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
                self._audio_out_io.write(pcm)
        elif ptype == TYPE_VIDEO_JPEG:
            img = qimage_from_jpeg(data)
            if img is not None:
                self._remote_view.setPixmap(
                    QtGui.QPixmap.fromImage(img).scaled(
                        self._remote_view.size(),
                        QtCore.Qt.AspectRatioMode.KeepAspectRatio,
                        QtCore.Qt.TransformationMode.SmoothTransformation,
                    )
                )
