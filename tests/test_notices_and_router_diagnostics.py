from __future__ import annotations

import pytest

from i2pchat.presentation import i18n
from i2pchat.presentation.notices import (
    LEVEL_PEER_STATE,
    LEVEL_USER,
    LEVEL_VERBOSE,
    NoticeLog,
    classify_error,
    classify_notice,
    render_notice,
)
from i2pchat.router.diagnostics import (
    ACTION_RESTART_ROUTER,
    diagnose_bundled_router,
    diagnose_log_text,
    diagnose_system_router,
)


@pytest.fixture(autouse=True)
def _english(monkeypatch):
    monkeypatch.setenv("I2PCHAT_LANG", "en")
    monkeypatch.setattr(i18n, "_language", None)


@pytest.mark.parametrize(
    "text,code,level",
    [
        ("Starting I2P session, please wait…", "session.starting", LEVEL_USER),
        ("Online! My Address: abcdef.b32.i2p", "session.online", LEVEL_USER),
        ("Tunnels may still be building. Wait 1–2 min before connecting.", "tunnels.slow", LEVEL_USER),
        ("Connecting to abcdefghijklmnopqrstuvwx... (may take 1–2 min)", "peer.connecting", LEVEL_PEER_STATE),
        ("Secure channel with PFS established", "peer.secure", LEVEL_PEER_STATE),
        ("Peer disconnected.", "peer.disconnected", LEVEL_PEER_STATE),
        ("Waiting for incoming connections...", "peer.waiting", LEVEL_PEER_STATE),
        ("BlindBox root secret rotated", "system.other", LEVEL_VERBOSE),
        ("Loaded identity from secure keyring", "system.other", LEVEL_VERBOSE),
        ("[BBDBG] poll", "system.other", LEVEL_VERBOSE),
    ],
)
def test_classify_notice_levels(text, code, level):
    notice = classify_notice(text)
    assert notice.code == code
    assert notice.level == level


def test_explicit_peer_is_kept():
    notice = classify_notice("Peer disconnected.", kind="info", peer="abc.b32.i2p")
    assert notice.peer == "abc.b32.i2p"
    assert notice.is_peer_state


def test_connection_errors_are_not_chat_noise():
    assert classify_error("Connection timed out. Check: I2P router running").code == "peer.connect_failed"
    assert classify_error("Secure handshake timed out. Reconnect.").level == LEVEL_VERBOSE
    other = classify_error("File too large")
    assert other.level == LEVEL_USER and other.code == "error.other"


def test_render_notice_localized(monkeypatch):
    notice = classify_notice("Group ready: Friends (3 peers).")
    assert render_notice(notice) == "Group “Friends” is ready (3 members)."
    monkeypatch.setattr(i18n, "_language", "ru")
    monkeypatch.delenv("I2PCHAT_LANG")
    assert render_notice(notice) == "Группа «Friends» готова (участников: 3)."
    unknown = classify_notice("Some brand new message")
    assert render_notice(unknown) == "Some brand new message"


def test_menu_language_overrides_env(monkeypatch):
    monkeypatch.setenv("I2PCHAT_LANG", "en")
    i18n.set_language("ru")
    assert i18n.current_language() == "en"
    i18n.set_language("ru", force=True)
    assert i18n.current_language() == "ru"
    assert i18n.tr("menu.language") == "Язык: Русский"
    i18n.set_language("en", force=True)
    assert i18n.tr("menu.language") == "Language: English"


def test_language_detection(monkeypatch):
    monkeypatch.delenv("I2PCHAT_LANG")
    monkeypatch.setenv("LC_ALL", "")
    monkeypatch.setenv("LC_MESSAGES", "")
    monkeypatch.setenv("LANG", "ru_RU.UTF-8")
    assert i18n.detect_language() == "ru"
    monkeypatch.setenv("LANG", "de_DE.UTF-8")
    monkeypatch.setattr(i18n.locale, "getlocale", lambda: (None, None))
    assert i18n.detect_language() == "en"


def test_notice_log_splits_levels():
    log = NoticeLog()
    log.add(classify_notice("Starting I2P session, please wait…"))
    log.add(classify_notice("Starting I2P session, please wait…"))
    log.add(classify_notice("BlindBox root secret rotated"))
    assert len(log.user) == 1
    assert len(log.verbose) == 2


RESEED_LOG = """\
12:00:01@123/warn - Reseed: SU3 download failed
12:00:05@123/warn - Reseed: SU3 download failed
12:00:09@123/warn - Reseed: Failed to reseed from servers. Waiting for 45 seconds
"""
SU3_ONLY_LOG = "Reseed: SU3 download failed\n" * 3
CLOCK_LOG = "12:00:01@1/warn - NTCP2: SessionCreated time difference 300 exceeds clock skew\n"
SSU2_CLOCK_LOG = "12:00:01@1/error - SSU2: Clock skew detected -200. Check your clock\n"
PORT_LOG = (
    "12:00:01@1/critical - Clients: Exception in SAM bridge: bind: Address already in use\n"
)
NET_LOG = "12:00:01@1/error - NTCP2: Connect error Network is unreachable\n"
QUIET_LOG = "12:00:01@1/info - NetDb: 120 routers loaded (10 floodfils)\n"


@pytest.mark.parametrize(
    "log,code",
    [
        (RESEED_LOG, "reseed_failed"),
        (SU3_ONLY_LOG, "reseed_failed"),
        (CLOCK_LOG, "clock_skew"),
        (SSU2_CLOCK_LOG, "clock_skew"),
        (PORT_LOG, "port_in_use"),
        (NET_LOG, "no_network"),
    ],
)
def test_diagnose_log_text(log, code):
    issue = diagnose_log_text(log)
    assert issue is not None and issue.code == code
    assert issue.message and not issue.message.startswith("diag.")


def test_diagnose_quiet_log():
    assert diagnose_log_text(QUIET_LOG) is None
    assert diagnose_log_text("") is None


def test_diagnose_bundled_router(tmp_path):
    log = tmp_path / "i2pd.log"
    log.write_text(QUIET_LOG)
    assert diagnose_bundled_router(str(log)) is None
    assert diagnose_bundled_router(str(log), warming_seconds=500).code == "tunnels_slow"
    crashed = diagnose_bundled_router(str(log), process_alive=False)
    assert crashed.code == "router_crashed" and ACTION_RESTART_ROUTER in crashed.actions
    log.write_text(RESEED_LOG)
    assert diagnose_bundled_router(str(log)).code == "reseed_failed"
    assert diagnose_bundled_router(str(log), network_ready=True) is None
    assert diagnose_bundled_router(str(tmp_path / "missing.log")) is None


def test_diagnose_system_router():
    assert diagnose_system_router("127.0.0.1", 7656, sam_reachable=True) is None
    issue = diagnose_system_router("127.0.0.1", 7656, sam_reachable=False)
    assert "127.0.0.1:7656" in issue.message
