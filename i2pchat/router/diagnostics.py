"""Turn router state and the i2pd log into advice the user can act on.

Patterns follow upstream i2pd log lines (libi2pd/Reseed.cpp, NTCP2.cpp,
SSU2Session.cpp, libi2pd_client/ClientContext.cpp). The Android app has the
same rules in ``RouterDiagnostics.kt``; keep them in sync.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from typing import Optional

from i2pchat.presentation.i18n import tr

ACTION_ROUTER_SETTINGS = "router_settings"
ACTION_RESTART_ROUTER = "restart_router"

_LOG_TAIL_BYTES = 256 * 1024
# Bind failures only matter at the latest startup. A 16 MiB router.log keeps
# "Address already in use" from hours ago inside the 256 KiB tail, which made
# Status keep showing "port busy" after a successful restart.
_PORT_IN_USE_RECENT_BYTES = 24 * 1024
# Several reseed hosts failing in a row means the user cannot reach any of them.
_SU3_FAILURES_FOR_RESEED_ISSUE = 3

_PORT_IN_USE = re.compile(
    r"Exception in SAM bridge|Unable to start SAM bridge|NTCP2: Failed to bind"
    r"|SSU2: Failed to open socket|Address already in use"
)
_CLOCK_SKEW = re.compile(r"exceeds clock skew|Clock skew detected")
_RESEED_GAVE_UP = re.compile(
    r"Reseed: Failed to reseed from servers|Reseed: Failed to reseed from \d+ servers"
    r"|Reseed: No reseed servers specified"
)
_SU3_FAILED = re.compile(r"Reseed: SU3 download failed")
_NO_NETWORK = re.compile(r"Network is unreachable|No route to host|Failed to start Transports")


@dataclass(frozen=True)
class RouterIssue:
    code: str
    params: dict[str, str] = field(default_factory=dict)
    actions: tuple[str, ...] = ()

    @property
    def message(self) -> str:
        return tr(f"diag.{self.code}", **self.params)


def read_log_tail(path: Optional[str], max_bytes: int = _LOG_TAIL_BYTES) -> str:
    if not path:
        return ""
    try:
        with open(path, "rb") as fh:
            fh.seek(0, os.SEEK_END)
            size = fh.tell()
            fh.seek(max(0, size - max_bytes))
            return fh.read().decode("utf-8", errors="replace")
    except OSError:
        return ""


def diagnose_log_text(log_text: str) -> Optional[RouterIssue]:
    """Most actionable problem visible in an i2pd log excerpt, if any."""
    if not log_text:
        return None
    recent = log_text[-_PORT_IN_USE_RECENT_BYTES:]
    if _PORT_IN_USE.search(recent):
        return RouterIssue(
            "port_in_use", actions=(ACTION_RESTART_ROUTER, ACTION_ROUTER_SETTINGS)
        )
    if _CLOCK_SKEW.search(log_text):
        return RouterIssue("clock_skew")
    reseed_failed = bool(_RESEED_GAVE_UP.search(log_text)) or (
        len(_SU3_FAILED.findall(log_text)) >= _SU3_FAILURES_FOR_RESEED_ISSUE
    )
    if _NO_NETWORK.search(log_text) and not reseed_failed:
        return RouterIssue("no_network")
    if reseed_failed:
        return RouterIssue("reseed_failed", actions=(ACTION_ROUTER_SETTINGS,))
    return None


def diagnose_system_router(host: str, port: int, *, sam_reachable: bool) -> Optional[RouterIssue]:
    if sam_reachable:
        return None
    return RouterIssue(
        "system_sam_unreachable",
        params={"addr": f"{host}:{port}"},
        actions=(ACTION_ROUTER_SETTINGS,),
    )


def diagnose_bundled_router(
    log_path: Optional[str],
    *,
    process_alive: bool = True,
    network_ready: bool = False,
    warming_seconds: float = 0.0,
    slow_after_seconds: float = 120.0,
) -> Optional[RouterIssue]:
    """Advice for the bundled i2pd; ``None`` while things look normal."""
    if network_ready:
        return None
    issue = diagnose_log_text(read_log_tail(log_path))
    if issue is not None:
        return issue
    if not process_alive:
        return RouterIssue("router_crashed", actions=(ACTION_RESTART_ROUTER,))
    if warming_seconds >= slow_after_seconds:
        return RouterIssue("tunnels_slow")
    return None
