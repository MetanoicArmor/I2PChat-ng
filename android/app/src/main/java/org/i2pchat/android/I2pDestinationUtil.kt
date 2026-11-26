package org.i2pchat.android

import android.util.Base64
import java.security.MessageDigest

/** Minimal I2P destination helpers (public slice + b32) for call DATAGRAM tokens. */
object I2pDestinationUtil {
    data class PublicDest(val base64: String, val base32: String)

    fun normalize(sessionStatusDest: String): PublicDest {
        val raw = decodeI2pB64(sessionStatusDest.trim())
        if (raw.size < 387) {
            return PublicDest(sessionStatusDest.trim(), "")
        }
        val certLen = ((raw[385].toInt() and 0xff) shl 8) or (raw[386].toInt() and 0xff)
        val publicLen = 387 + certLen
        val publicBytes =
            if (publicLen in 1 until raw.size) raw.copyOf(publicLen) else raw
        val b64 = encodeI2pB64(publicBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(publicBytes)
        val b32 = base32Lower(digest).take(52)
        return PublicDest(b64, b32)
    }

    fun decodeI2pB64(value: String): ByteArray {
        val std = value.trim()
            .replace('-', '+')
            .replace('~', '/')
        return Base64.decode(std, Base64.DEFAULT)
    }

    /** Bare b32 of a datagram address, or empty when it cannot be derived. */
    fun datagramIdentity(token: String): String {
        val text = token.trim()
        if (text.isEmpty()) return ""
        var host = text.lowercase()
        if (host.endsWith(".b32.i2p")) host = host.removeSuffix(".b32.i2p")
        if (host.length == 52 && host.all { it in "abcdefghijklmnopqrstuvwxyz234567" }) return host
        if (text.length < 80 || '.' in text) return ""
        return runCatching {
            val b32 = normalize(text).base32
            if (b32.length == 52) b32 else ""
        }.getOrDefault("")
    }

    /**
     * True when [fromDest] is the destination advertised for this call.
     * Compared by b32, so two base64 spellings of one destination still match.
     */
    fun sameDatagramSender(fromDest: String, remoteToken: String): Boolean {
        val left = datagramIdentity(fromDest)
        val right = datagramIdentity(remoteToken)
        if (left.isNotEmpty() && right.isNotEmpty()) return left == right
        val advertised = fromDest.trim()
        val expected = remoteToken.trim()
        return advertised.isNotEmpty() && advertised == expected
    }

    fun encodeI2pB64(data: ByteArray): String {
        val std = Base64.encodeToString(data, Base64.NO_WRAP)
        return std.replace('+', '-').replace('/', '~')
    }

    private fun base32Lower(data: ByteArray): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                out.append(alphabet[(buffer shr bitsLeft) and 0x1f])
            }
        }
        if (bitsLeft > 0) {
            out.append(alphabet[(buffer shl (5 - bitsLeft)) and 0x1f])
        }
        return out.toString()
    }
}
