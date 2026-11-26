package org.i2pchat.android

import java.security.MessageDigest
import java.security.SecureRandom

object CallSignals {
    private val secureRandom = SecureRandom()
    private val callIdPattern = Regex("^[A-Za-z0-9._:-]{1,80}$")
    private val b32Pattern = Regex("^[a-z2-7]{52}(?:\\.b32\\.i2p)?$")
    private val namingPattern = Regex("^[a-z2-7]{52}\\.b32\\.i2p$")
    private val fullDestPattern = Regex("^[A-Za-z0-9~=-]{80,1024}$")

    const val INVITE = "CALL_INVITE"
    const val ACCEPT = "CALL_ACCEPT"
    const val REJECT = "CALL_REJECT"
    const val HANGUP = "CALL_HANGUP"
    const val MEDIA = "CALL_MEDIA"
    const val DEFAULT_CODECS = "opus,vp8,jpeg"

    fun localOfferCodecs(): String {
        // Offer only what we can *decode*: the peer picks its encoder from this list.
        val parts = mutableListOf<String>()
        if (OpusFrameDecoder.isSupported()) parts.add("opus")
        if (Vp8FrameDecoder.isSupported()) parts.add("vp8")
        parts.add("jpeg")
        return parts.joinToString(",")
    }

    fun intersectCodecs(local: String, remote: String): String {
        val localList = local.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .ifEmpty { DEFAULT_CODECS.split(",") }
        val remoteSet = remote.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .ifEmpty { DEFAULT_CODECS.split(",") }
            .toSet()
        val chosen = localList.filter { it in remoteSet }.toMutableList()
        if ("jpeg" !in chosen) chosen.add("jpeg")
        return chosen.joinToString(",").ifBlank { "jpeg" }
    }

    fun codecsInclude(codecs: String, name: String): Boolean =
        codecs.split(",").any { it.trim().equals(name, ignoreCase = true) }

    fun newCallId(): String {
        val bytes = ByteArray(8)
        secureRandom.nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${System.currentTimeMillis() / 1000}-$hex"
    }

    fun isCallId(id: String): Boolean = callIdPattern.matches(id)

    /** b32 host or full I2P destination, safe to place in a SAM command. */
    fun isDatagramToken(token: String): Boolean {
        val text = token.trim()
        if (text.isEmpty()) return false
        if (b32Pattern.matches(text.lowercase())) return true
        return fullDestPattern.matches(text)
    }

    /** Name passed to ``NAMING LOOKUP`` — only a ``<b32>.b32.i2p`` host. */
    fun isNamingName(name: String): Boolean = namingPattern.matches(name.trim().lowercase())

    /** Chat peer ids are base32 hosts; the ``.b32.i2p`` suffix is optional. */
    fun samePeerKey(raw: String): String = raw.trim().lowercase().removeSuffix(".b32.i2p")

    fun samePeer(left: String, right: String): Boolean {
        val a = samePeerKey(left)
        val b = samePeerKey(right)
        return a.isNotEmpty() && a == b
    }

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
        val codecs: String = DEFAULT_CODECS,
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
                codecs = parts[3].ifBlank { DEFAULT_CODECS },
                maxBitrate = parts[4].toIntOrNull() ?: 320_000,
                datagramB32 = parts[5],
            ) else null
            ACCEPT -> if (parts.size >= 5) Parsed(
                kind = ACCEPT,
                callId = parts[1],
                codecs = parts[2].ifBlank { DEFAULT_CODECS },
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
