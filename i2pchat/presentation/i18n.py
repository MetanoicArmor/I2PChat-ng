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
        "Message. Enter = send, Shift+Enter / Ctrl+J / Alt+Enter = new line. "
        "Type /help for commands."
    ),
    "tui.compose_help": (
        "Compose: Enter sends; Shift+Enter, Ctrl+Enter (if your terminal reports it), "
        "Ctrl+J or Alt+Enter insert a new line; Ctrl+S also sends."
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
        "Сообщение. Enter — отправить, Shift+Enter / Ctrl+J / Alt+Enter — новая строка. "
        "/help — список команд."
    ),
    "tui.compose_help": (
        "Ввод: Enter отправляет; Shift+Enter, Ctrl+Enter (если терминал его различает), "
        "Ctrl+J или Alt+Enter — новая строка; Ctrl+S тоже отправляет."
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


def set_language(lang: str) -> None:
    """Override detection (e.g. from ``QLocale().name()``); unknown codes fall back to en."""
    global _language
    if _normalize(os.environ.get("I2PCHAT_LANG", "")):
        return
    _language = _normalize(lang) or "en"


def has_key(key: str) -> bool:
    return key in _EN


def tr(key: str, **params: object) -> str:
    text = _CATALOGS.get(current_language(), _EN).get(key) or _EN.get(key) or key
    return text.format(**params) if params else text
