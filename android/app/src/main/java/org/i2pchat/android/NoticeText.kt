package org.i2pchat.android

import android.content.Context

/** Localized text for a notice; unknown codes fall back to the engine's English text. */
fun renderNotice(context: Context, notice: NoticeUi): String {
    val peer = shortPeer(notice.arg)
    return when (notice.code) {
        "session.starting" -> context.getString(R.string.notice_session_starting)
        "tunnels.building" -> context.getString(R.string.notice_tunnels_building)
        "session.online" -> context.getString(R.string.notice_session_online)
        "tunnels.ready" -> context.getString(R.string.notice_tunnels_ready)
        "tunnels.slow" -> context.getString(R.string.notice_tunnels_slow)
        "profile.transient" -> context.getString(R.string.notice_profile_transient)
        "router.starting" -> context.getString(R.string.notice_router_starting)
        "router.restarting" -> context.getString(R.string.notice_router_restarting)
        "router.waiting" -> context.getString(R.string.notice_router_waiting, notice.arg)
        "router.sam_ready" -> context.getString(R.string.notice_router_sam_ready, notice.arg)
        "trust.prompt" -> context.getString(R.string.notice_trust_prompt, peer)
        "file.offer" -> context.getString(R.string.notice_file_offer, peer) + " " + notice.text
        "file.offer_stale" -> context.getString(R.string.notice_file_offer_stale, notice.arg)
        "peer.connect_failed" -> context.getString(R.string.notice_connect_failed, peer)
        "peer.connecting" -> context.getString(R.string.notice_peer_connecting, peer)
        "peer.secure" -> context.getString(R.string.notice_peer_secure, peer)
        "peer.disconnected" -> context.getString(R.string.notice_peer_disconnected, peer)
        else -> notice.text.ifBlank { notice.code }
    }
}

fun noticeIsError(notice: NoticeUi): Boolean =
    notice.code.startsWith("error.") || notice.code == "peer.connect_failed"

fun noticeIsSuccess(notice: NoticeUi): Boolean =
    notice.code == "session.online" || notice.code == "tunnels.ready"

fun shortPeer(addr: String): String {
    val p = addr.removeSuffix(".b32.i2p")
    return if (p.length > 14) p.take(12) + "…" else p
}
