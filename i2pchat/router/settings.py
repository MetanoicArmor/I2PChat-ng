from __future__ import annotations

from dataclasses import asdict, dataclass
import ipaddress
import json
import logging
import os
from typing import Any, Optional

from i2pchat.core.i2p_chat_core import get_profiles_dir
from i2pchat.storage.blindbox_state import atomic_write_json

logger = logging.getLogger("i2pchat.router.settings")


_ROUTER_SETTINGS_FILE = "router_prefs.json"
_DISABLE_BUNDLED_ENV = "I2PCHAT_DISABLE_BUNDLED_I2PD"
# Opt-in for pointing the "system" SAM backend at a non-loopback host (e.g. an
# I2P router on another machine on a trusted LAN). Off by default so a stray or
# malicious config cannot silently send traffic to a remote SAM bridge.
_ALLOW_REMOTE_SAM_ENV = "I2PCHAT_ALLOW_REMOTE_SAM"
_DISABLE_BUNDLED_MARKERS = (
    "/usr/share/i2pchat/system-router-only",
    "/usr/local/share/i2pchat/system-router-only",
)

DEFAULT_TUNNEL_LENGTH = 4
# Zero hops would expose the client's own router as the tunnel endpoint.
MIN_TUNNEL_LENGTH = 1
MAX_TUNNEL_LENGTH = 7


def allow_remote_system_sam() -> bool:
    return os.environ.get(_ALLOW_REMOTE_SAM_ENV, "").strip().lower() in {
        "1",
        "true",
        "yes",
        "on",
    }


def _is_loopback_host(text: str) -> bool:
    if text.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(text).is_loopback
    except ValueError:
        return False


def _acceptable_sam_host(text: str) -> bool:
    """IPv4, IPv6 or a hostname. Rejects spaces and control characters."""
    if not text or len(text) > 253 or any(ord(ch) < 33 for ch in text):
        return False
    if _is_loopback_host(text):
        return True
    try:
        ipaddress.ip_address(text)
        return True
    except ValueError:
        pass
    if ".." in text:
        return False
    labels = text.split(".")
    return all(
        label
        and len(label) <= 63
        and label.replace("-", "").isalnum()
        and not label.startswith("-")
        and not label.endswith("-")
        for label in labels
    )


def system_sam_host_error(host: str, *, allow_remote: bool) -> Optional[str]:
    """Why this host cannot be saved, or ``None`` when it can.

    The UI calls this before saving so a rejected address stays on screen with
    an explanation instead of being silently rewritten to loopback.
    """
    text = host.strip()
    if not text:
        return "Enter a SAM host."
    if not _acceptable_sam_host(text):
        return "SAM host must be an IPv4 address, an IPv6 address or a hostname."
    if _is_loopback_host(text) or allow_remote or allow_remote_system_sam():
        return None
    return (
        "This address is not on this computer. Turn on “Router on another machine” "
        "to use a router on the local network or Yggdrasil. The SAM connection is not encrypted."
    )


def require_system_sam_host(
    value: Any,
    default: str = "127.0.0.1",
    *,
    allow_remote: bool = False,
) -> str:
    """Constrain the system SAM host to loopback unless remote SAM is opted in.

    A non-loopback host without the opt-in (the saved ``allow_remote_sam`` flag
    or ``I2PCHAT_ALLOW_REMOTE_SAM``) is coerced back to the loopback default
    (fail-safe for a file the user did not just confirm) rather than trusting a
    remote router, which would carry all traffic in the clear to a third party.
    """
    text = _coerce_string_setting(value, default)
    if system_sam_host_error(text, allow_remote=allow_remote) is None:
        return text
    logger.warning(
        "Ignoring system SAM host %r (enable allow_remote_sam or set %s=1); using %s",
        text,
        _ALLOW_REMOTE_SAM_ENV,
        default,
    )
    return default


@dataclass
class RouterSettings:
    # Fresh install / no router_prefs.json: system SAM (portable & winget builds often ship without i2pd).
    backend: str = "system"  # "system" | "bundled"

    system_sam_host: str = "127.0.0.1"
    system_sam_port: int = 7656

    bundled_sam_host: str = "127.0.0.1"
    bundled_sam_port: int = 17656

    bundled_http_proxy_port: int = 14444
    bundled_socks_proxy_port: int = 14447
    bundled_control_http_port: int = 17070

    bundled_auto_start: bool = False

    # Hops per inbound and outbound tunnel (I2CP inbound.length / outbound.length).
    tunnel_length: int = DEFAULT_TUNNEL_LENGTH

    # System SAM may point at another machine (LAN or Yggdrasil). Off by default:
    # SAM itself is not encrypted.
    allow_remote_sam: bool = False


def coerce_tunnel_length(value: Any) -> int:
    return _coerce_int_setting(
        value,
        DEFAULT_TUNNEL_LENGTH,
        minimum=MIN_TUNNEL_LENGTH,
        maximum=MAX_TUNNEL_LENGTH,
    )


def bundled_i2pd_allowed() -> bool:
    raw = os.environ.get(_DISABLE_BUNDLED_ENV, "").strip().lower()
    if raw in {"1", "true", "yes", "on"}:
        return False
    return not any(os.path.isfile(path) for path in _DISABLE_BUNDLED_MARKERS)


def normalize_router_settings(settings: RouterSettings) -> RouterSettings:
    settings = RouterSettings(
        **{
            **asdict(settings),
            "bundled_sam_host": require_loopback_host(settings.bundled_sam_host),
            "system_sam_host": require_system_sam_host(
                settings.system_sam_host,
                allow_remote=settings.allow_remote_sam,
            ),
            "tunnel_length": coerce_tunnel_length(settings.tunnel_length),
            "allow_remote_sam": bool(settings.allow_remote_sam),
        }
    )
    if bundled_i2pd_allowed():
        return settings
    if settings.backend != "bundled" and not settings.bundled_auto_start:
        return settings
    return RouterSettings(
        backend="system",
        system_sam_host=settings.system_sam_host,
        system_sam_port=settings.system_sam_port,
        bundled_sam_host=settings.bundled_sam_host,
        bundled_sam_port=settings.bundled_sam_port,
        bundled_http_proxy_port=settings.bundled_http_proxy_port,
        bundled_socks_proxy_port=settings.bundled_socks_proxy_port,
        bundled_control_http_port=settings.bundled_control_http_port,
        bundled_auto_start=False,
        tunnel_length=settings.tunnel_length,
        allow_remote_sam=settings.allow_remote_sam,
    )


def router_settings_path() -> str:
    return os.path.join(get_profiles_dir(), _ROUTER_SETTINGS_FILE)


def router_runtime_dir() -> str:
    path = os.path.join(get_profiles_dir(), "router")
    os.makedirs(path, mode=0o700, exist_ok=True)
    if os.name == "posix":
        try:
            os.chmod(path, 0o700)
        except OSError:
            logger.debug("Could not tighten permissions on %s", path)
    return path


def _coerce_string_setting(value: Any, default: str) -> str:
    text = str(value).strip() if value is not None else ""
    return text or default


def require_loopback_host(value: Any, default: str = "127.0.0.1") -> str:
    """Return a loopback-only host suitable for a bundled router bind."""
    text = _coerce_string_setting(value, default)
    if text.lower() == "localhost":
        return text
    try:
        if ipaddress.ip_address(text).is_loopback:
            return text
    except ValueError:
        pass
    raise ValueError("Bundled SAM host must be a loopback address")


def _coerce_int_setting(
    value: Any,
    default: int,
    *,
    minimum: int = 1,
    maximum: int = 65535,
) -> int:
    try:
        if isinstance(value, str):
            value = value.strip()
        coerced = int(value)
    except (TypeError, ValueError):
        return default
    if coerced < minimum or coerced > maximum:
        return default
    return coerced


def _coerce_bool_setting(value: Any, default: bool) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        text = value.strip().lower()
        if text in {"1", "true", "yes", "on"}:
            return True
        if text in {"0", "false", "no", "off"}:
            return False
    return default


def _coerce_router_settings(raw: dict[str, Any]) -> RouterSettings:
    defaults = asdict(RouterSettings())
    backend = _coerce_string_setting(raw.get("backend"), defaults["backend"]).lower()
    if backend not in {"system", "bundled"}:
        backend = defaults["backend"]
    return RouterSettings(
        backend=backend,
        allow_remote_sam=_coerce_bool_setting(raw.get("allow_remote_sam"), False),
        system_sam_host=require_system_sam_host(
            raw.get("system_sam_host"),
            defaults["system_sam_host"],
            allow_remote=_coerce_bool_setting(raw.get("allow_remote_sam"), False),
        ),
        system_sam_port=_coerce_int_setting(
            raw.get("system_sam_port"),
            defaults["system_sam_port"],
        ),
        bundled_sam_host=require_loopback_host(
            raw.get("bundled_sam_host"),
            defaults["bundled_sam_host"],
        ),
        bundled_sam_port=_coerce_int_setting(
            raw.get("bundled_sam_port"),
            defaults["bundled_sam_port"],
        ),
        bundled_http_proxy_port=_coerce_int_setting(
            raw.get("bundled_http_proxy_port"),
            defaults["bundled_http_proxy_port"],
        ),
        bundled_socks_proxy_port=_coerce_int_setting(
            raw.get("bundled_socks_proxy_port"),
            defaults["bundled_socks_proxy_port"],
        ),
        bundled_control_http_port=_coerce_int_setting(
            raw.get("bundled_control_http_port"),
            defaults["bundled_control_http_port"],
        ),
        bundled_auto_start=_coerce_bool_setting(
            raw.get("bundled_auto_start"),
            defaults["bundled_auto_start"],
        ),
        tunnel_length=coerce_tunnel_length(raw.get("tunnel_length")),
    )


def load_router_settings() -> RouterSettings:
    path = router_settings_path()
    try:
        with open(path, "r", encoding="utf-8") as f:
            raw = json.load(f)
        if isinstance(raw, dict):
            return normalize_router_settings(_coerce_router_settings(raw))
    except Exception:
        pass
    return normalize_router_settings(RouterSettings())


def save_router_settings(settings: RouterSettings) -> None:
    atomic_write_json(router_settings_path(), asdict(normalize_router_settings(settings)))
