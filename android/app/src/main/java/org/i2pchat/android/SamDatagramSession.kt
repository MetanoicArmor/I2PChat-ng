package org.i2pchat.android

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * SAM v3 DATAGRAM session for i2pd.
 *
 * SESSION STATUS does not return a UDP PORT. Incoming packets are forwarded to
 * HOST:PORT from SESSION CREATE (our local UDP socket). Outgoing packets use
 * `DATAGRAM SEND` on the control TCP socket.
 */
class SamDatagramSession private constructor(
    val sessionId: String,
    val localDestinationB64: String,
    val localB32Hint: String,
    private val control: Socket,
    private val out: BufferedOutputStream,
    private val udp: DatagramSocket,
    private val onDatagram: (fromDest: String, payload: ByteArray) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val sendLock = Any()
    private var recvThread: Thread? = null

    fun startRecv() {
        if (recvThread != null) return
        recvThread = thread(name = "sam-datagram-recv", isDaemon = true) {
            val buf = ByteArray(64 * 1024)
            while (!closed.get()) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    udp.receive(packet)
                    if (packet.length <= 0 || packet.length > MAX_RECV_BYTES) continue
                    val data = packet.data.copyOf(packet.length)
                    val nl = data.indexOf('\n'.code.toByte())
                    if (nl <= 0) continue
                    val from = String(data, 0, nl, StandardCharsets.US_ASCII).trim()
                    val payload = data.copyOfRange(nl + 1, data.size)
                    if (from.isNotBlank()) onDatagram(from, payload)
                } catch (_: Exception) {
                    if (closed.get()) break
                }
            }
        }
    }

    fun send(destinationB64: String, payload: ByteArray) {
        if (closed.get()) return
        require(payload.size <= MAX_SEND_PAYLOAD) { "DATAGRAM payload too large: ${payload.size}" }
        require(CallSignals.isDatagramToken(destinationB64)) { "DATAGRAM destination rejected" }
        val header = "DATAGRAM SEND SIZE=${payload.size} DESTINATION=$destinationB64\n"
            .toByteArray(StandardCharsets.US_ASCII)
        synchronized(sendLock) {
            out.write(header)
            out.write(payload)
            out.flush()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { udp.close() }
        runCatching { out.close() }
        runCatching { control.close() }
    }

    companion object {
        // i2pd reads a whole DATAGRAM SEND into an 8192-byte buffer (SAM_SOCKET_BUFFER_SIZE).
        const val MAX_SEND_PAYLOAD = 7_000
        const val MAX_RECV_BYTES = 16 * 1024

        fun open(
            sessionId: String,
            samHost: String,
            samPort: Int,
            onDatagram: (String, ByteArray) -> Unit,
            /** Extra I2CP options for SESSION CREATE, e.g. `inbound.length`. */
            options: Map<String, String> = emptyMap(),
        ): SamDatagramSession {
            val udp = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            val localUdpPort = udp.localPort
            val sock = Socket()
            sock.connect(InetSocketAddress(samHost, samPort), 15_000)
            sock.soTimeout = 180_000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
            val out = BufferedOutputStream(sock.getOutputStream())
            out.write("HELLO VERSION MIN=3.0 MAX=3.2\n".toByteArray(StandardCharsets.US_ASCII))
            out.flush()
            expectOk(reader.readLine(), "HELLO")
            val extra = options.entries
                .filter { it.key.isNotBlank() && it.value.none { c -> c.isWhitespace() } }
                .joinToString("") { " ${it.key}=${it.value}" }
            val create =
                "SESSION CREATE STYLE=DATAGRAM ID=$sessionId DESTINATION=TRANSIENT " +
                    "SIGNATURE_TYPE=7 HOST=127.0.0.1 PORT=$localUdpPort$extra\n"
            out.write(create.toByteArray(StandardCharsets.US_ASCII))
            out.flush()
            val status = reader.readLine() ?: throw IllegalStateException("Empty SESSION STATUS")
            val fields = parseFields(status)
            if (fields["RESULT"]?.equals("OK", true) != true && fields["DESTINATION"].isNullOrBlank()) {
                sock.close()
                udp.close()
                throw IllegalStateException("SESSION CREATE failed: $status")
            }
            val dest = fields["DESTINATION"].orEmpty()
            if (dest.isBlank()) {
                sock.close()
                udp.close()
                throw IllegalStateException("SESSION STATUS missing DESTINATION")
            }
            val normalized = I2pDestinationUtil.normalize(dest)
            // Keep a BufferedInputStream reference so the TCP control socket stays open;
            // we do not read DATAGRAM RECEIVED here because PORT forwarding is enabled.
            BufferedInputStream(sock.getInputStream())
            val session = SamDatagramSession(
                sessionId = sessionId,
                localDestinationB64 = normalized.base64,
                localB32Hint = normalized.base32,
                control = sock,
                out = out,
                udp = udp,
                onDatagram = onDatagram,
            )
            session.startRecv()
            return session
        }

        /** Resolve a .b32.i2p (or other) name to an I2P-base64 destination via SAM. */
        fun namingLookup(samHost: String, samPort: Int, name: String): String {
            val sock = Socket()
            sock.connect(InetSocketAddress(samHost, samPort), 15_000)
            sock.soTimeout = 60_000
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                val out = BufferedOutputStream(sock.getOutputStream())
                out.write("HELLO VERSION MIN=3.0 MAX=3.2\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()
                expectOk(reader.readLine(), "HELLO")
                val safe = name.trim()
                require(CallSignals.isNamingName(safe)) { "NAMING LOOKUP name rejected" }
                out.write("NAMING LOOKUP NAME=$safe\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush()
                val line = reader.readLine() ?: throw IllegalStateException("Empty NAMING LOOKUP reply")
                val fields = parseFields(line)
                if (fields["RESULT"]?.equals("OK", true) != true) {
                    throw IllegalStateException("NAMING LOOKUP failed: $line")
                }
                val value = fields["VALUE"].orEmpty()
                if (value.isBlank()) throw IllegalStateException("NAMING LOOKUP missing VALUE")
                return value
            } finally {
                runCatching { sock.close() }
            }
        }

        private fun expectOk(line: String?, topic: String) {
            if (line.isNullOrBlank()) throw IllegalStateException("Empty $topic reply")
            val fields = parseFields(line)
            val result = fields["RESULT"]
            if (result != null && !result.equals("OK", true)) {
                throw IllegalStateException("$topic failed: $line")
            }
        }

        private fun parseFields(line: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            line.trim().split(' ').drop(2).forEach { token ->
                val i = token.indexOf('=')
                if (i > 0) out[token.substring(0, i).uppercase()] = token.substring(i + 1)
            }
            return out
        }
    }
}
