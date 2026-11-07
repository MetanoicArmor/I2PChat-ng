"""Service notices: classify core messages into user / verbose / peer-state.

The core emits free-form English text for its service messages. Frontends
should not dump all of it into chat windows: most of it is technical noise
(handshakes, BlindBox roots, identity loading) that only matters when
debugging. Each message gets a stable ``code`` and a ``level``:

- ``user``: the user can act on it or needs it to know the app is ready;
- ``verbose``: technical detail, shown only with the "Verbose log" setting;
- ``peer_state``: connect / disconnect churn; updates the contact status
  badge instead of producing a line in the chat.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Optional

LEVEL_USER = "user"
LEVEL_VERBOSE = "verbose"
LEVEL_PEER_STATE = "peer_state"

SEVERITY_INFO = "info"
SEVERITY_SUCCESS = "success"
SEVERITY_WARNING = "warning"
SEVERITY_ERROR = "error"


@dataclass(frozen=True)
class Notice:
    code: str
    level: str
    text: str
    severity: str = SEVERITY_INFO
    peer: Optional[str] = None
    params: dict[str, str] = field(default_factory=dict)

    @property
    def is_user(self) -> bool:
        return self.level == LEVEL_USER

    @property
    def is_peer_state(self) -> bool:
        return self.level == LEVEL_PEER_STATE


@dataclass(frozen=True)
class _Rule:
    pattern: re.Pattern[str]
    code: str
    level: str
    severity: str = SEVERITY_INFO


def _r(pattern: str, code: str, level: str, severity: str = SEVERITY_INFO) -> _Rule:
    return _Rule(re.compile(pattern), code, level, severity)


_U, _V, _P = LEVEL_USER, LEVEL_VERBOSE, LEVEL_PEER_STATE

_SYSTEM_RULES: tuple[_Rule, ...] = (
    _r(r"^Starting I2P session", "session.starting", _U),
    _r(r"^Building I2P tunnels", "tunnels.building", _U),
    _r(r"^Online! My Address:\s*(?P<addr>\S+)", "session.online", _U, SEVERITY_SUCCESS),
    _r(r"^Tunnels ready", "tunnels.ready", _U, SEVERITY_SUCCESS),
    _r(r"^Tunnels may still be building", "tunnels.slow", _U, SEVERITY_WARNING),
    _r(r"^Tunnels confirmed\. You are now VISIBLE", "tunnels.visible", _U, SEVERITY_SUCCESS),
    _r(r"^Security note: TRANSIENT profile", "profile.transient", _U, SEVERITY_WARNING),
    _r(r"^Warning: BlindBox insecure local mode", "blindbox.insecure_local", _U, SEVERITY_WARNING),
    _r(r"^Warning: could not read profile \.dat", "profile.dat_unreadable", _U, SEVERITY_WARNING),
    _r(r"^BlindBox polling is slow", "blindbox.slow", _U, SEVERITY_WARNING),
    _r(r"^Trusted key change was not approved", "trust.change_rejected", _U, SEVERITY_WARNING),
    _r(r"^Auto-pin is NOT out-of-band verified", "trust.autopin_unverified", _U, SEVERITY_WARNING),
    _r(r"^Keys confirmed\. Accept the Trust dialog", "trust.accept_dialog", _U),
    _r(r"^Accepted first contact from (?P<peer>\S+?)\.\.\.", "contact.first_contact", _U),
    _r(r"^Added to Saved peers: (?P<peer>\S+?)\.\.\.", "contact.saved", _U, SEVERITY_SUCCESS),
    _r(r"^Group ready: (?P<title>.+) \((?P<count>\d+) peers\)\.$", "group.ready", _U, SEVERITY_SUCCESS),
    _r(r"^Group updated: (?P<title>.+) \((?P<count>\d+) peers\)\.$", "group.updated", _U),
    _r(r"^Joined group: (?P<title>.+) \((?P<count>\d+) peers\)\.$", "group.joined", _U, SEVERITY_SUCCESS),
    _r(r"^Already in group: (?P<title>.+)\.$", "group.already_member", _U),
    # Connection churn: the contact badge shows it, the chat stays clean.
    _r(r"^Connecting to (?P<peer>\S+?)(\.\.\.|…)", "peer.connecting", _P),
    _r(r"^Connection attempt already in progress", "peer.connecting", _P),
    _r(r"^Already connected to this peer", "peer.already_connected", _P),
    _r(r"^Handshake sent", "peer.handshaking", _P),
    _r(r"^Initiating secure handshake", "peer.handshaking", _P),
    _r(r"^Waiting for peer response", "peer.handshaking", _P),
    _r(r"^Session keys derived", "peer.handshaking", _P),
    _r(r"^Secure channel with (?P<peer>\S+?)… established", "peer.secure", _P, SEVERITY_SUCCESS),
    _r(r"^Secure channel with PFS established", "peer.secure", _P, SEVERITY_SUCCESS),
    _r(r"^✔ Ready! You can now send messages", "peer.ready", _P, SEVERITY_SUCCESS),
    _r(r"^Connection accepted from (?P<peer>\S+?)\.\.\.", "peer.incoming", _P),
    _r(r"^Peer Identity:", "peer.identity", _P),
    _r(r"^You disconnected from (?P<peer>\S+?)\.\.\.", "peer.disconnected_local", _P),
    _r(r"^Disconnected from (?P<peer>\S+?)…", "peer.disconnected", _P),
    _r(r"^Peer disconnected", "peer.disconnected", _P),
    _r(r"^Peer requested disconnect", "peer.disconnected", _P),
    _r(r"^Waiting for incoming connections", "peer.waiting", _P),
)

_ERROR_RULES: tuple[_Rule, ...] = (
    _r(r"^Connection timed out", "peer.connect_failed", _U, SEVERITY_WARNING),
    _r(r"^Connection failed:", "peer.connect_failed", _U, SEVERITY_WARNING),
    _r(r"^Secure handshake timed out", "peer.handshake_timeout", _V, SEVERITY_WARNING),
    _r(r"^Handshake ", "peer.handshake_failed", _V, SEVERITY_WARNING),
    _r(r"^Rejected incoming connection", "peer.incoming_rejected", _V, SEVERITY_WARNING),
    _r(r"^Cannot reach I2P SAM at (?P<addr>\S+?)\.", "router.sam_unreachable", _U, SEVERITY_ERROR),
    _r(r"^Blind ?Box ", "blindbox.error", _V, SEVERITY_WARNING),
)


def _match(rules: tuple[_Rule, ...], text: str) -> Optional[tuple[_Rule, dict[str, str]]]:
    for rule in rules:
        m = rule.pattern.search(text)
        if m is not None:
            return rule, {k: v for k, v in m.groupdict().items() if v is not None}
    return None


def classify_notice(
    text: str,
    *,
    kind: str = "system",
    code: Optional[str] = None,
    peer: Optional[str] = None,
) -> Notice:
    """Map a core service message (``system`` / ``info`` / ``success``) to a Notice."""
    raw = (text or "").strip()
    hit = _match(_SYSTEM_RULES, raw)
    if code is None and hit is None:
        severity = SEVERITY_SUCCESS if kind == "success" else SEVERITY_INFO
        return Notice("system.other", LEVEL_VERBOSE, raw, severity, peer)
    params: dict[str, str] = {}
    level, severity = LEVEL_VERBOSE, SEVERITY_INFO
    if hit is not None:
        rule, params = hit
        level, severity = rule.level, rule.severity
        if code is None or code == rule.code:
            code = rule.code
    peer = peer or params.get("peer")
    return Notice(code or "system.other", level, raw, severity, peer, params)


def classify_error(text: str, *, peer: Optional[str] = None) -> Notice:
    """Errors are user-level unless they are connection churn or protocol detail."""
    raw = (text or "").strip()
    hit = _match(_ERROR_RULES, raw)
    if hit is None:
        return Notice("error.other", LEVEL_USER, raw, SEVERITY_ERROR, peer)
    rule, params = hit
    return Notice(rule.code, rule.level, raw, rule.severity, peer or params.get("peer"), params)


def render_notice(notice: Notice) -> str:
    """Localized one-line text; falls back to the core's English text."""
    from i2pchat.presentation.i18n import has_key, tr

    key = f"notice.{notice.code}"
    if not has_key(key):
        return notice.text
    params = dict(notice.params)
    if notice.peer and "peer" not in params:
        params["peer"] = notice.peer
    if "peer" in params:
        params["peer"] = short_peer(params["peer"])
    try:
        return tr(key, **params)
    except (KeyError, IndexError, ValueError):
        return notice.text


def short_peer(peer: str) -> str:
    p = (peer or "").strip()
    if p.endswith(".b32.i2p"):
        p = p[: -len(".b32.i2p")]
    return p[:12] + "…" if len(p) > 14 else p


class NoticeLog:
    """Bounded user / verbose notice buffers shared by GUI and TUI status views."""

    def __init__(self, *, max_user: int = 200, max_verbose: int = 1000) -> None:
        from collections import deque
        from datetime import datetime

        self._now = datetime.now
        self.user: deque[tuple[str, Notice]] = deque(maxlen=max_user)
        self.verbose: deque[tuple[str, Notice]] = deque(maxlen=max_verbose)

    def add(self, notice: Notice) -> None:
        stamp = self._now().strftime("%H:%M:%S")
        if notice.level == LEVEL_USER:
            if self.user and self.user[-1][1].text == notice.text:
                return
            self.user.append((stamp, notice))
        self.verbose.append((stamp, notice))
