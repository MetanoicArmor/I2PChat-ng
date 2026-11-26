"""SAM DATAGRAM session for i2pd / Java I2P SAM v3.

i2pd's SESSION STATUS for STYLE=DATAGRAM does **not** return a UDP PORT.
Incoming datagrams are forwarded to HOST:PORT from SESSION CREATE (our local
UDP socket). Outgoing datagrams are sent with ``DATAGRAM SEND`` on the control
TCP socket (SAM v1/v2-compatible path used by i2pd).
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Awaitable, Callable, Optional

from .client import SAMClient, SessionHandle
from .destination import Destination
from .errors import ProtocolError, SessionClosed
from .protocol import build_session_create, expect_ok, parse_reply_line

logger = logging.getLogger(__name__)

OnDatagram = Callable[[str, bytes], Awaitable[None] | None]

# i2pd reads the whole ``DATAGRAM SEND`` (header line + payload) into an 8192-byte
# buffer (SAM_SOCKET_BUFFER_SIZE); anything larger stalls the control socket.
MAX_SEND_PAYLOAD = 7_000


@dataclass(slots=True)
class DatagramSession:
    """TRANSIENT DATAGRAM session: control TCP + local UDP for inbound."""

    session_id: str
    local_destination_b64: str
    local_b32: str
    local_udp_port: int
    _control: SessionHandle
    _transport: asyncio.DatagramTransport
    _protocol: "_DatagramProtocol"
    _send_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    _reader_task: Optional[asyncio.Task] = None
    tx_packets: int = 0
    tx_bytes: int = 0
    tx_errors: int = 0
    rx_packets: int = 0
    rx_bytes: int = 0
    control_closed: bool = False
    first_rx_at: Optional[float] = None

    def stats(self) -> dict[str, int | float | bool | None]:
        return {
            "tx_packets": self.tx_packets,
            "tx_bytes": self.tx_bytes,
            "tx_errors": self.tx_errors,
            "rx_packets": self.rx_packets,
            "rx_bytes": self.rx_bytes,
            "control_closed": self.control_closed,
            "first_rx_at": self.first_rx_at,
        }

    async def send(self, destination_b64_or_b32: str, payload: bytes) -> None:
        if self.control_closed or self._control.writer.is_closing():
            raise SessionClosed(message="DATAGRAM control socket closed")
        dest = destination_b64_or_b32.strip()
        if not dest or any(c.isspace() for c in dest):
            raise ValueError("DATAGRAM send needs a destination (base64 or <b32>.b32.i2p)")
        if len(payload) > MAX_SEND_PAYLOAD:
            raise ValueError(f"DATAGRAM payload too large ({len(payload)} > {MAX_SEND_PAYLOAD})")
        # i2pd: DATAGRAM SEND SIZE=n DESTINATION=...\n + n raw bytes. i2pd 3.x also
        # resolves ``<b32>.b32.i2p`` here (SAMSession::SendDatagram), so a full
        # base64 destination is preferred but not strictly required.
        header = f"DATAGRAM SEND SIZE={len(payload)} DESTINATION={dest}\n".encode("ascii")
        try:
            async with self._send_lock:
                self._control.writer.write(header + payload)
                await self._control.writer.drain()
        except Exception:
            self.tx_errors += 1
            raise
        self.tx_packets += 1
        self.tx_bytes += len(payload)

    def note_received(self, size: int) -> None:
        self.rx_packets += 1
        self.rx_bytes += size
        if self.first_rx_at is None:
            self.first_rx_at = time.monotonic()
            logger.info("DATAGRAM %s: first inbound packet (%d bytes)", self.session_id, size)

    async def close(self) -> None:
        task = self._reader_task
        self._reader_task = None
        if task is not None:
            task.cancel()
        try:
            self._transport.close()
        except Exception:
            pass
        await self._control.close()


async def _watch_control(dg: "DatagramSession") -> None:
    """Drain the SAM control socket: log anything i2pd says and notice closure.

    With HOST/PORT forwarding i2pd normally writes nothing here, so any data or
    EOF means the session is in trouble (e.g. a malformed/oversized DATAGRAM SEND).
    """
    reader = dg._control.reader
    try:
        while True:
            data = await reader.read(4096)
            if not data:
                break
            logger.warning(
                "DATAGRAM %s: unexpected SAM control data: %r",
                dg.session_id,
                data[:200],
            )
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        logger.warning("DATAGRAM %s: control read error: %s", dg.session_id, exc)
    dg.control_closed = True
    logger.warning(
        "DATAGRAM %s: SAM control socket closed (tx=%d rx=%d)",
        dg.session_id,
        dg.tx_packets,
        dg.rx_packets,
    )


class _DatagramProtocol(asyncio.DatagramProtocol):
    def __init__(self) -> None:
        self.on_datagram: Optional[OnDatagram] = None
        self.owner: Optional["DatagramSession"] = None
        self.transport: Optional[asyncio.DatagramTransport] = None
        try:
            self.loop = asyncio.get_running_loop()
        except RuntimeError:
            self.loop = asyncio.get_event_loop()

    def connection_made(self, transport: asyncio.BaseTransport) -> None:
        self.transport = transport  # type: ignore[assignment]

    def datagram_received(self, data: bytes, addr) -> None:  # noqa: ANN001
        # i2pd forwards repliable datagrams as: <from_dest>\n<payload>
        if b"\n" not in data:
            return
        header, payload = data.split(b"\n", 1)
        try:
            from_dest = header.decode("ascii").strip()
        except UnicodeDecodeError:
            return
        if not from_dest:
            return
        owner = self.owner
        if owner is not None:
            owner.note_received(len(payload))
        cb = self.on_datagram
        if cb is None:
            return
        result = cb(from_dest, payload)
        if asyncio.iscoroutine(result):
            self.loop.create_task(result)

    def error_received(self, exc: Exception) -> None:
        logger.debug("DATAGRAM UDP error: %s", exc)


async def create_datagram_session(
    session_id: str,
    *,
    sam_address: tuple[str, int] = ("127.0.0.1", 7656),
    destination: str | Destination | None = None,
    options: dict[str, str] | None = None,
    on_datagram: Optional[OnDatagram] = None,
    session_create_timeout: float = 180.0,
) -> DatagramSession:
    """Open a SAM DATAGRAM session and a local UDP socket for inbound packets."""
    loop = asyncio.get_running_loop()
    protocol = _DatagramProtocol()
    protocol.on_datagram = on_datagram
    transport, _ = await loop.create_datagram_endpoint(
        lambda: protocol,
        local_addr=("127.0.0.1", 0),
    )
    sockname = transport.get_extra_info("sockname")
    local_udp_port = int(sockname[1])

    client = SAMClient(
        sam_address=sam_address,
        session_create_timeout=session_create_timeout,
    )
    await client.open()

    if destination is None:
        destination_text = "TRANSIENT"
        sig_type = Destination.default_sig_type
    elif isinstance(destination, Destination):
        if destination.private_key is None:
            transport.close()
            await client.close()
            raise ValueError("SESSION CREATE requires a private destination")
        destination_text = destination.private_key.base64
        sig_type = None
    else:
        if str(destination).strip() == "TRANSIENT":
            destination_text = "TRANSIENT"
            sig_type = Destination.default_sig_type
        else:
            destination_obj = Destination(destination, has_private_key=True)
            if destination_obj.private_key is None:
                transport.close()
                await client.close()
                raise ValueError("SESSION CREATE requires a private destination")
            destination_text = destination_obj.private_key.base64
            sig_type = None

    # PORT/HOST tell i2pd where to forward *incoming* datagrams (UDP).
    opts = dict(options or {})
    opts.setdefault("HOST", "127.0.0.1")
    opts["PORT"] = str(local_udp_port)

    payload = build_session_create(
        "DATAGRAM",
        session_id,
        destination_text,
        sig_type=sig_type,
        options=opts,
    )
    assert client._writer is not None and client._reader is not None
    client._writer.write(payload)
    await client._writer.drain()
    try:
        line = await asyncio.wait_for(
            client._reader.readline(),
            timeout=session_create_timeout,
        )
        reply = expect_ok(parse_reply_line(line))
    except Exception:
        transport.close()
        await client.close()
        raise

    # i2pd: SESSION STATUS RESULT=OK DESTINATION=<privkey or pub>  (no PORT field)
    local_dest = reply.fields.get("DESTINATION") or ""
    if not local_dest:
        transport.close()
        await client.close()
        raise ProtocolError(
            message="SESSION STATUS missing DESTINATION for DATAGRAM",
            raw_line=reply.raw_line,
        )

    local_b32 = ""
    try:
        # SESSION STATUS may return the private key; Destination still derives b32.
        local_b32 = Destination(local_dest, has_private_key=True).base32 + ".b32.i2p"
    except Exception:
        try:
            local_b32 = Destination(local_dest).base32 + ".b32.i2p"
        except Exception:
            local_b32 = ""

    # Prefer the public destination for dialing us when STATUS gave a private key.
    public_b64 = local_dest
    try:
        dest_obj = Destination(local_dest, has_private_key=True)
        if dest_obj.base64:
            public_b64 = dest_obj.base64
    except Exception:
        pass

    handle = SessionHandle(
        session_id=session_id,
        reader=client._reader,
        writer=client._writer,
    )
    client._reader = None
    client._writer = None

    session = DatagramSession(
        session_id=session_id,
        local_destination_b64=public_b64,
        local_b32=local_b32,
        local_udp_port=local_udp_port,
        _control=handle,
        _transport=transport,
        _protocol=protocol,
    )
    protocol.owner = session
    session._reader_task = loop.create_task(
        _watch_control(session),
        name=f"datagram-control-{session_id}",
    )
    return session


async def naming_lookup_b64(
    name: str,
    *,
    sam_address: tuple[str, int] = ("127.0.0.1", 7656),
) -> str:
    client = SAMClient(sam_address=sam_address)
    await client.open()
    try:
        return await client.naming_lookup(name)
    finally:
        await client.close()
