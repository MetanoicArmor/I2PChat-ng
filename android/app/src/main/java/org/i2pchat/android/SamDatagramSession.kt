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
        fun open(
            sessionId: String,
            samHost: String,
            samPort: Int,
            onDatagram: (String, ByteArray) -> Unit,
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
            val create =
                "SESSION CREATE STYLE=DATAGRAM ID=$sessionId DESTINATION=TRANSIENT " +
                    "SIGNATURE_TYPE=7 HOST=127.0.0.1 PORT=$localUdpPort\n"
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
            // Keep a BufferedInputStream reference so the TCP control socket stays open;
            // we do not read DATAGRAM RECEIVED here because PORT forwarding is enabled.
            BufferedInputStream(sock.getInputStream())
            val session = SamDatagramSession(
                sessionId = sessionId,
                localDestinationB64 = dest,
                localB32Hint = dest.take(16),
                control = sock,
                out = out,
                udp = udp,
                onDatagram = onDatagram,
            )
            session.startRecv()
            return session
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
