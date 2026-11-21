package org.i2pchat.android

import java.security.MessageDigest
import kotlin.random.Random

object CallSignals {
    const val INVITE = "CALL_INVITE"
    const val ACCEPT = "CALL_ACCEPT"
    const val REJECT = "CALL_REJECT"
    const val HANGUP = "CALL_HANGUP"
    const val MEDIA = "CALL_MEDIA"

    fun newCallId(): String = "${System.currentTimeMillis() / 1000}-${Random.nextBytes(8).joinToString("") { "%02x".format(it) }}"

    fun callIdHash16(callId: String): ByteArray {
        val dig = MessageDigest.getInstance("SHA-256").digest(callId.toByteArray(Charsets.UTF_8))
        return dig.copyOf(16)
    }

    fun buildInvite(
        callId: String,
        video: Boolean,
        codecs: String,
        maxBitrate: Int,
        datagramB32: String,
    ): String {
        val media = if (video) "video" else "audio"
        return "$INVITE|$callId|$media|$codecs|$maxBitrate|$datagramB32"
    }

    fun buildAccept(callId: String, codecs: String, maxBitrate: Int, datagramB32: String): String =
        "$ACCEPT|$callId|$codecs|$maxBitrate|$datagramB32"

    fun buildReject(callId: String, reason: String = "declined"): String =
        "$REJECT|$callId|${reason.replace("|", "_").take(64)}"

    fun buildHangup(callId: String): String = "$HANGUP|$callId"

    fun buildMedia(callId: String, mic: Boolean, cam: Boolean, screen: Boolean, spk: Boolean): String =
        "$MEDIA|$callId|mic=${b(mic)}|cam=${b(cam)}|screen=${b(screen)}|spk=${b(spk)}"

    data class Parsed(
        val kind: String,
        val callId: String,
        val wantVideo: Boolean = false,
        val codecs: String = "opus,jpeg",
        val maxBitrate: Int = 320_000,
        val datagramB32: String = "",
        val reason: String = "",
        val mic: Boolean = true,
        val cam: Boolean = false,
        val screen: Boolean = false,
        val spk: Boolean = true,
    )

    fun parse(payload: String): Parsed? {
        val parts = payload.trim().split("|")
        if (parts.isEmpty()) return null
        return when (parts[0]) {
            INVITE -> if (parts.size >= 6) Parsed(
                kind = INVITE,
                callId = parts[1],
                wantVideo = parts[2].equals("video", ignoreCase = true),
                codecs = parts[3].ifBlank { "opus,jpeg" },
                maxBitrate = parts[4].toIntOrNull() ?: 320_000,
                datagramB32 = parts[5],
            ) else null
            ACCEPT -> if (parts.size >= 5) Parsed(
                kind = ACCEPT,
                callId = parts[1],
                codecs = parts[2].ifBlank { "opus,jpeg" },
                maxBitrate = parts[3].toIntOrNull() ?: 320_000,
                datagramB32 = parts[4],
            ) else null
            REJECT -> if (parts.size >= 2) Parsed(
                kind = REJECT,
                callId = parts[1],
                reason = parts.getOrNull(2) ?: "declined",
            ) else null
            HANGUP -> if (parts.size >= 2) Parsed(kind = HANGUP, callId = parts[1]) else null
            MEDIA -> if (parts.size >= 3) {
                val flags = parts.drop(2).associate {
                    val kv = it.split("=", limit = 2)
                    (kv.getOrNull(0) ?: "") to ((kv.getOrNull(1) ?: "") == "1")
                }
                Parsed(
                    kind = MEDIA,
                    callId = parts[1],
                    mic = flags["mic"] ?: true,
                    cam = flags["cam"] ?: false,
                    screen = flags["screen"] ?: false,
                    spk = flags["spk"] ?: true,
                )
            } else null
            else -> null
        }
    }

    private fun b(v: Boolean) = if (v) "1" else "0"
}
