"""Minimal ru/en string catalog for the desktop GUI and TUI.

Covers service notices, the Status view, router diagnostics advice and
contact link states. Language comes from ``I2PCHAT_LANG``, then the usual
locale environment variables, then the OS locale; the Qt GUI may override it
with :func:`set_language` from ``QLocale``.
"""

from __future__ import annotations

import locale
import os
from typing import Optional

SUPPORTED_LANGUAGES = ("en", "ru")

_EN: dict[str, str] = {
    # Service notices
    "notice.session.starting": "Starting I2P session…",
    "notice.tunnels.building": "Building I2P tunnels, this usually takes 1–2 minutes…",
    "notice.session.online": "Online. Your address is ready to share.",
    "notice.tunnels.ready": "Tunnels are ready. You can chat now.",
    "notice.tunnels.slow": "Tunnels are still being built. Wait 1–2 minutes before connecting.",
    "notice.tunnels.visible": "Tunnels confirmed. Other users can reach you.",
    "notice.profile.transient": "One-time profile: trusted contact keys are not kept after restart.",
    "notice.profile.dat_unreadable": "The profile file could not be read; a backup identity was used.",
    "notice.blindbox.insecure_local": "Offline delivery runs in insecure local mode.",
    "notice.blindbox.slow": "Offline delivery servers respond slowly; messages may be delayed.",
    "notice.trust.change_rejected": "A contact's key changed and was not approved; the chat stays blocked.",
    "notice.trust.autopin_unverified": "A contact key was trusted automatically without verification.",
    "notice.trust.accept_dialog": "Confirm the contact's key in the Trust dialog to start chatting.",
    "notice.contact.first_contact": "New contact {peer} added.",
    "notice.contact.saved": "Contact {peer} saved.",
    "notice.group.ready": "Group “{title}” is ready ({count} members).",
    "notice.group.updated": "Group “{title}” updated ({count} members).",
    "notice.group.joined": "Joined group “{title}” ({count} members).",
    "notice.group.already_member": "You are already in group “{title}”.",
    "notice.peer.connecting": "Connecting to {peer}…",
    "notice.peer.already_connected": "Already connected.",
    "notice.peer.handshaking": "Setting up an encrypted channel…",
    "notice.peer.secure": "Encrypted channel established.",
    "notice.peer.ready": "Connected. You can send messages.",
    "notice.peer.incoming": "{peer} connected to you.",
    "notice.peer.disconnected": "Contact disconnected.",
    "notice.peer.disconnected_local": "You disconnected.",
    "notice.peer.waiting": "Waiting for incoming connections.",
    "notice.peer.connect_failed": (
        "Could not connect to the contact. They may be offline, or the I2P "
        "tunnels are not ready yet. Messages will be sent offline when possible."
    ),
    "notice.router.sam_unreachable": "Cannot reach the I2P router (SAM at {addr}).",
    # Contact link state
    "peer_link.secure": "online",
    "peer_link.connecting": "connecting…",
    "peer_link.offline": "offline",
    "peer_link.offline_blindbox": "offline · delivery via BlindBox",
    # Status view
    "status.title": "Status",
    "status.open_hint": "Click to open the full status.",
    "status.close_hint": "Click to return to the chat.",
    "status.chats": "Chats",
    "status.ready": "Ready to chat",
    "status.not_ready": "Not ready yet",
    "status.check.router": "I2P router",
    "status.check.sam": "Router connection (SAM)",
    "status.check.tunnels": "Tunnels",
    "status.check.address": "Your address",
    "status.check.blindbox": "Offline delivery (BlindBox)",
    "status.router.bundled": "built-in i2pd",
    "status.router.system": "system router at {addr}",
    "status.state.ok": "OK",
    "status.state.pending": "in progress",
    "status.state.failed": "problem",
    "status.state.off": "off",
    "status.events": "Events",
    "status.verbose": "Verbose log",
    "status.verbose_hint": "Technical messages: handshakes, BlindBox, connects and disconnects.",
    "status.no_events": "No events yet.",
    "status.problem": "Problem",
    "status.copy_address": "Copy address",
    "status.router_settings": "Router settings",
    "status.restart_router": "Restart built-in i2pd",
    "menu.language": "Language: English",
    "menu.language.hint": "Switches this window between English and Russian.",
    "ui.send": "Send",
    "ui.connect": "Connect",
    "ui.disconnect": "Disconnect",
    "ui.groups": "Groups",
    "ui.new": "New",
    "ui.saved_peers": "Saved peers",
    "ui.search_placeholder": "Search in this chat…",
    "ui.search_prev": "Previous match",
    "ui.search_next": "Next match",
    "ui.addr_placeholder": "Peer base32 address (optional .b32.i2p when pasting)",
    "ui.compose_enter": (
        "Type message. Enter = send; Shift+Enter = new line; {send_mod} also sends. "
        "Drag and drop images or files to send."
    ),
    "ui.compose_newline": (
        "Type message. Enter = new line; Shift+Enter = new line; {send_mod} = send. "
        "Drag and drop images or files to send."
    ),
    "menu.load_profile": "Load profile (.dat)",
    "menu.send_picture": "Send picture",
    "menu.send_file": "Send file",
    "menu.new_group": "New text group…",
    "menu.join_group": "Join group via invite…",
    "menu.blindbox": "BlindBox diagnostics",
    "menu.export_profile": "Export profile backup…",
    "menu.import_profile": "Import profile backup…",
    "menu.export_history": "Export history backup…",
    "menu.import_history": "Import history backup…",
    "menu.updates": "Check for updates…",
    "menu.open_dir": "Open App dir",
    "menu.router": "I2P router…",
    "menu.forget_key": "Forget pinned peer key",
    "menu.copy_address": "Copy my address",
    "menu.history_on": "Chat history: ON",
    "menu.history_off": "Chat history: OFF",
    "menu.clear_history": "Clear history",
    "menu.incoming_files": "Incoming files…",
    "menu.profile_password": "Profile password…",
    "menu.retention": "History retention…",
    "menu.privacy_on": "Privacy mode: ON",
    "menu.privacy_off": "Privacy mode: OFF",
    "menu.enter_on": "Enter sends message: ON",
    "menu.enter_off": "Enter sends message: OFF",
    "menu.sound_on": "Notification sound: ON",
    "menu.sound_off": "Notification sound: OFF",
    "status.network_not_ready": "Network is not ready. Open Status for details.",
    # Router diagnostics
    "diag.system_sam_unreachable": (
        "Cannot connect to the system i2pd router at {addr}. Make sure the router "
        "is running, SAM is enabled in it, and the address and port are correct."
    ),
    "diag.reseed_failed": (
        "Could not download the initial list of I2P nodes (reseed). Check your "
        "internet connection and the system clock, use a proxy for the first start, "
        "or reseed manually if your provider blocks access."
    ),
    "diag.clock_skew": (
        "The system clock differs too much from network time. Enable automatic "
        "time synchronization and restart."
    ),
    "diag.port_in_use": (
        "A router port is already in use, probably by an old i2pd process. "
        "Restart the built-in i2pd or change the ports in router settings."
    ),
    "diag.no_network": (
        "The router has no network connectivity. Check your internet connection."
    ),
    "diag.tunnels_slow": (
        "The I2P network is warming up. This usually takes 1–2 minutes on first start."
    ),
    "diag.router_crashed": (
        "The built-in i2pd router stopped unexpectedly. Restart it from router settings."
    ),
    # TUI
    "tui.compose_placeholder": (
        "Message. Enter = send, Ctrl+J = new line. Type /help for commands."
    ),
    "tui.compose_help": (
        "Compose: Enter sends; Ctrl+J inserts a new line; Ctrl+S also sends."
    ),
    "tui.status_hint": "F9 / Esc: back to chat · V: toggle verbose log",
    "tui.verbose_on": "Verbose log enabled.",
    "tui.verbose_off": "Verbose log disabled.",
}

_RU: dict[str, str] = {
    "notice.session.starting": "Запускаем сессию I2P…",
    "notice.tunnels.building": "Строим туннели I2P, обычно это занимает 1–2 минуты…",
    "notice.session.online": "В сети. Ваш адрес готов, им можно делиться.",
    "notice.tunnels.ready": "Туннели готовы. Можно общаться.",
    "notice.tunnels.slow": "Туннели ещё строятся. Подождите 1–2 минуты перед подключением.",
    "notice.tunnels.visible": "Туннели подтверждены. Другие пользователи могут до вас достучаться.",
    "notice.profile.transient": "Одноразовый профиль: доверенные ключи контактов не сохранятся после перезапуска.",
    "notice.profile.dat_unreadable": "Не удалось прочитать файл профиля; использована резервная копия ключей.",
    "notice.blindbox.insecure_local": "Офлайн-доставка работает в небезопасном локальном режиме.",
    "notice.blindbox.slow": "Серверы офлайн-доставки отвечают медленно; сообщения могут задерживаться.",
    "notice.trust.change_rejected": "Ключ контакта изменился и не был подтверждён; переписка заблокирована.",
    "notice.trust.autopin_unverified": "Ключ контакта принят автоматически, без сверки.",
    "notice.trust.accept_dialog": "Подтвердите ключ контакта в окне доверия, чтобы начать переписку.",
    "notice.contact.first_contact": "Добавлен новый контакт {peer}.",
    "notice.contact.saved": "Контакт {peer} сохранён.",
    "notice.group.ready": "Группа «{title}» готова (участников: {count}).",
    "notice.group.updated": "Группа «{title}» обновлена (участников: {count}).",
    "notice.group.joined": "Вы вступили в группу «{title}» (участников: {count}).",
    "notice.group.already_member": "Вы уже состоите в группе «{title}».",
    "notice.peer.connecting": "Подключаемся к {peer}…",
    "notice.peer.already_connected": "Уже подключено.",
    "notice.peer.handshaking": "Устанавливаем зашифрованный канал…",
    "notice.peer.secure": "Зашифрованный канал установлен.",
    "notice.peer.ready": "Подключено. Можно отправлять сообщения.",
    "notice.peer.incoming": "{peer} подключился к вам.",
    "notice.peer.disconnected": "Собеседник отключился.",
    "notice.peer.disconnected_local": "Вы отключились.",
    "notice.peer.waiting": "Ожидаем входящих подключений.",
    "notice.peer.connect_failed": (
        "Не удалось подключиться к собеседнику. Возможно, он не в сети или туннели "
        "I2P ещё не готовы. Сообщения будут доставлены офлайн, когда это возможно."
    ),
    "notice.router.sam_unreachable": "Нет связи с роутером I2P (SAM по адресу {addr}).",
    "peer_link.secure": "на связи",
    "peer_link.connecting": "подключаюсь…",
    "peer_link.offline": "не в сети",
    "peer_link.offline_blindbox": "не в сети · доставка через BlindBox",
    "status.title": "Статус",
    "status.open_hint": "Нажмите, чтобы открыть подробный статус.",
    "status.close_hint": "Нажмите, чтобы вернуться в чат.",
    "status.chats": "Чаты",
    "status.ready": "Готово к работе",
    "status.not_ready": "Ещё не готово",
    "status.check.router": "Роутер I2P",
    "status.check.sam": "Связь с роутером (SAM)",
    "status.check.tunnels": "Туннели",
    "status.check.address": "Ваш адрес",
    "status.check.blindbox": "Офлайн-доставка (BlindBox)",
    "status.router.bundled": "встроенный i2pd",
    "status.router.system": "системный роутер {addr}",
    "status.state.ok": "готово",
    "status.state.pending": "в процессе",
    "status.state.failed": "проблема",
    "status.state.off": "выключено",
    "status.events": "События",
    "status.verbose": "Подробный лог",
    "status.verbose_hint": "Технические сообщения: рукопожатия, BlindBox, подключения и отключения.",
    "status.no_events": "Событий пока нет.",
    "status.problem": "Проблема",
    "status.copy_address": "Скопировать адрес",
    "status.router_settings": "Настройки роутера",
    "status.restart_router": "Перезапустить встроенный i2pd",
    "menu.language": "Язык: Русский",
    "menu.language.hint": "Переключает это окно между русским и английским.",
    "ui.send": "Отправить",
    "ui.connect": "Подключить",
    "ui.disconnect": "Отключить",
    "ui.groups": "Группы",
    "ui.new": "Новая",
    "ui.saved_peers": "Сохранённые",
    "ui.search_placeholder": "Поиск в этом чате…",
    "ui.search_prev": "Предыдущее совпадение",
    "ui.search_next": "Следующее совпадение",
    "ui.addr_placeholder": "Адрес собеседника (можно вставить .b32.i2p)",
    "ui.compose_enter": (
        "Сообщение. Enter — отправить, Shift+Enter — новая строка, {send_mod} тоже отправляет. "
        "Картинки и файлы можно перетащить сюда."
    ),
    "ui.compose_newline": (
        "Сообщение. Enter — новая строка, Shift+Enter — новая строка, {send_mod} — отправить. "
        "Картинки и файлы можно перетащить сюда."
    ),
    "menu.load_profile": "Загрузить профиль (.dat)",
    "menu.send_picture": "Отправить картинку",
    "menu.send_file": "Отправить файл",
    "menu.new_group": "Новая группа…",
    "menu.join_group": "Вступить по приглашению…",
    "menu.blindbox": "Диагностика BlindBox",
    "menu.export_profile": "Экспорт профиля…",
    "menu.import_profile": "Импорт профиля…",
    "menu.export_history": "Экспорт истории…",
    "menu.import_history": "Импорт истории…",
    "menu.updates": "Проверить обновления…",
    "menu.open_dir": "Папка приложения",
    "menu.router": "Роутер I2P…",
    "menu.forget_key": "Забыть ключ собеседника",
    "menu.copy_address": "Копировать мой адрес",
    "menu.history_on": "История чата: вкл",
    "menu.history_off": "История чата: выкл",
    "menu.clear_history": "Очистить историю",
    "menu.incoming_files": "Входящие файлы…",
    "menu.profile_password": "Пароль профиля…",
    "menu.retention": "Хранение истории…",
    "menu.privacy_on": "Приватный режим: вкл",
    "menu.privacy_off": "Приватный режим: выкл",
    "menu.enter_on": "Enter отправляет: вкл",
    "menu.enter_off": "Enter отправляет: выкл",
    "menu.sound_on": "Звук уведомлений: вкл",
    "menu.sound_off": "Звук уведомлений: выкл",
    "status.network_not_ready": "Сеть не готова. Подробности — в разделе «Статус».",
    "diag.system_sam_unreachable": (
        "Не могу подключиться к системному i2pd по адресу {addr}. Проверьте, что "
        "роутер запущен, в нём включён SAM, а адрес и порт указаны верно."
    ),
    "diag.reseed_failed": (
        "Не удалось загрузить начальный список узлов I2P (ресид). Проверьте "
        "подключение к интернету и синхронизацию системных часов, используйте "
        "прокси при первом запуске или выполните ресид вручную доступным вам "
        "способом, если ваш провайдер блокирует доступ."
    ),
    "diag.clock_skew": (
        "Системное время сильно расходится с сетевым. Включите автоматическую "
        "синхронизацию времени и перезапустите приложение."
    ),
    "diag.port_in_use": (
        "Порт роутера уже занят — вероятно, старым процессом i2pd. Перезапустите "
        "встроенный i2pd или смените порты в настройках роутера."
    ),
    "diag.no_network": "У роутера нет доступа к сети. Проверьте подключение к интернету.",
    "diag.tunnels_slow": (
        "Сеть I2P прогревается. При первом запуске это обычно занимает 1–2 минуты."
    ),
    "diag.router_crashed": (
        "Встроенный роутер i2pd неожиданно остановился. Перезапустите его в настройках роутера."
    ),
    "tui.compose_placeholder": (
        "Сообщение. Enter — отправить, Ctrl+J — новая строка. /help — список команд."
    ),
    "tui.compose_help": (
        "Ввод: Enter отправляет; Ctrl+J — новая строка; Ctrl+S тоже отправляет."
    ),
    "tui.status_hint": "F9 / Esc — назад в чат · V — подробный лог вкл/выкл",
    "tui.verbose_on": "Подробный лог включён.",
    "tui.verbose_off": "Подробный лог выключен.",
}

_CATALOGS: dict[str, dict[str, str]] = {"en": _EN, "ru": _RU}
_language: Optional[str] = None


def _normalize(lang: str) -> Optional[str]:
    code = (lang or "").strip().lower().replace("-", "_").split(".")[0].split("_")[0]
    return code if code in SUPPORTED_LANGUAGES else None


def detect_language() -> str:
    forced = _normalize(os.environ.get("I2PCHAT_LANG", ""))
    if forced:
        return forced
    for var in ("LC_ALL", "LC_MESSAGES", "LANG", "LANGUAGE"):
        value = os.environ.get(var, "")
        if value:
            for part in value.split(":"):
                hit = _normalize(part)
                if hit:
                    return hit
            if var != "LANGUAGE":
                break
    try:
        loc = locale.getlocale()[0] or ""
    except (ValueError, TypeError):
        loc = ""
    return _normalize(loc) or "en"


def current_language() -> str:
    global _language
    if _language is None:
        _language = detect_language()
    return _language


def set_language(lang: str, *, force: bool = False) -> None:
    """Override detection (e.g. from ``QLocale().name()``); unknown codes fall back to en.

    ``I2PCHAT_LANG`` still wins unless ``force`` is set. The ⋯ menu uses
    ``force`` so a saved choice is not ignored on the next click.
    """
    global _language
    if not force and _normalize(os.environ.get("I2PCHAT_LANG", "")):
        return
    _language = _normalize(lang) or "en"


def has_key(key: str) -> bool:
    return key in _EN


def tr(key: str, **params: object) -> str:
    text = _CATALOGS.get(current_language(), _EN).get(key) or _EN.get(key) or key
    return text.format(**params) if params else text
