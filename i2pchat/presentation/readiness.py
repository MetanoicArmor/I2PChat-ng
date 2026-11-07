"""Readiness checklist and router advice shared by the Qt Status page and TUI."""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any, Optional

from i2pchat.presentation.i18n import tr
from i2pchat.router.diagnostics import (
    ACTION_RESTART_ROUTER,
    RouterIssue,
    diagnose_log_text,
    diagnose_system_router,
    read_log_tail,
)
from i2pchat.router.runtime import is_tcp_open

STATE_OK = "ok"
STATE_PENDING = "pending"
STATE_FAILED = "failed"
STATE_OFF = "off"


@dataclass(frozen=True)
class ReadinessCheck:
    label: str
    value: str
    state: str


@dataclass(frozen=True)
class Readiness:
    ready: bool
    checks: tuple[ReadinessCheck, ...]
    issue_text: Optional[str]
    can_restart: bool


def transport_state(core: Any) -> str:
    if core is None:
        return "stopped"
    try:
        return str(core.session_manager.transport_state.value)
    except Exception:
        return "stopped"


def active_sam_reachable(bundled_manager: Any) -> bool:
    if bundled_manager is None:
        return False
    try:
        host, port = bundled_manager.sam_address()
    except Exception:
        return False
    return is_tcp_open(host, int(port))


class ReadinessTracker:
    """Keeps the start error and last router issue between periodic refreshes."""

    def __init__(self) -> None:
        self.start_error: Optional[str] = None
        self.issue: Optional[RouterIssue] = None
        self.started_mono = time.monotonic()

    def session_starting(self) -> None:
        self.start_error = None
        self.issue = None
        self.started_mono = time.monotonic()

    def session_failed(self, message: str, router_settings: Any) -> None:
        self.start_error = message
        if router_settings.backend != "bundled":
            host = router_settings.system_sam_host
            port = int(router_settings.system_sam_port)
            self.issue = diagnose_system_router(
                host, port, sam_reachable=is_tcp_open(host, port)
            )

    def _refresh_issue(self, router_settings: Any, bundled_manager: Any, core: Any) -> None:
        if router_settings.backend != "bundled":
            if self.start_error is None:
                self.issue = None
            return
        if bundled_manager is None:
            if self.start_error is None:
                self.issue = None
            return
        if self.start_error is not None:
            try:
                log_path: Optional[str] = bundled_manager.log_path()
            except RuntimeError:
                log_path = None
            found = diagnose_log_text(read_log_tail(log_path)) if log_path else None
            # Stale "Address already in use" must not stick after a later bind
            # succeeded: if our SAM port is open, the current router is fine.
            if (
                found is not None
                and found.code == "port_in_use"
                and active_sam_reachable(bundled_manager)
            ):
                found = None
            self.issue = found
            return
        self.issue = bundled_manager.diagnose(
            network_ready=transport_state(core) == "ready",
            warming_seconds=time.monotonic() - self.started_mono,
        )

    def evaluate(
        self,
        *,
        core: Any,
        router_settings: Any,
        bundled_manager: Any,
        active_sam: Optional[tuple[str, int]],
    ) -> Readiness:
        self._refresh_issue(router_settings, bundled_manager, core)
        started = self.start_error is None
        transport = transport_state(core)
        bundled = router_settings.backend == "bundled"
        if bundled:
            router_value = tr("status.router.bundled")
            if not started:
                router_state = STATE_FAILED
            elif bundled_manager is not None and bundled_manager.is_running():
                router_state = STATE_OK
            else:
                router_state = STATE_PENDING
        else:
            router_value = tr(
                "status.router.system",
                addr=f"{router_settings.system_sam_host}:{router_settings.system_sam_port}",
            )
            if not started:
                router_state = STATE_FAILED
            elif active_sam is not None:
                router_state = STATE_OK
            else:
                router_state = STATE_PENDING
        sam_value = f"{active_sam[0]}:{active_sam[1]}" if active_sam else ""
        if not started or transport == "failed":
            sam_state = STATE_FAILED
        elif transport in ("stopped", "starting"):
            sam_state = STATE_PENDING
        else:
            sam_state = STATE_OK
        if transport == "ready":
            tunnels_state = STATE_OK
        elif transport == "failed" or not started:
            tunnels_state = STATE_FAILED
        else:
            tunnels_state = STATE_PENDING
        my_addr = ""
        if core is not None and getattr(core, "my_dest", None) is not None:
            my_addr = str(core.my_dest.base32) + ".b32.i2p"
        addr_state = STATE_OK if my_addr else (STATE_FAILED if not started else STATE_PENDING)
        bb_state, bb_value = STATE_OFF, ""
        if core is not None:
            try:
                bb = core.get_blindbox_telemetry()
                if bb.get("enabled"):
                    bb_value = str(bb.get("state", "off"))
                    bb_state = STATE_OK if bb_value == "ready" else STATE_PENDING
            except Exception:
                bb_state = STATE_OFF
        checks = (
            ReadinessCheck(tr("status.check.router"), router_value, router_state),
            ReadinessCheck(tr("status.check.sam"), sam_value, sam_state),
            ReadinessCheck(
                tr("status.check.tunnels"),
                "" if transport == "stopped" else transport,
                tunnels_state,
            ),
            ReadinessCheck(tr("status.check.address"), my_addr, addr_state),
            ReadinessCheck(tr("status.check.blindbox"), bb_value, bb_state),
        )
        ready = all(c.state in (STATE_OK, STATE_OFF) for c in checks[:4])
        issue_text: Optional[str] = None
        can_restart = False
        if self.issue is not None:
            issue_text = self.issue.message
            can_restart = bundled and ACTION_RESTART_ROUTER in self.issue.actions
        elif self.start_error is not None:
            issue_text = self.start_error
            can_restart = bundled
        return Readiness(ready, checks, issue_text, can_restart)
