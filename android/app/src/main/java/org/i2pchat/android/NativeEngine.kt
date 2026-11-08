package org.i2pchat.android

object NativeEngine {
    val available: Boolean = try {
        System.loadLibrary("i2pchat_jni")
        true
    } catch (_: Throwable) {
        false
    }

    interface Listener {
        /** JSON `{code, level, arg, text}`; replaces [onSystem] for service messages. */
        fun onNotice(json: String)
        fun onSystem(message: String)
        fun onError(message: String)
        fun onHistory(peer: String, json: String)
        fun onDelivery(json: String)
        fun onPeerState(peer: String, state: String, reason: String)
        fun onTransportState(state: String, reason: String)
        fun onTransfer(peer: String, json: String)
        fun onFileReceived(peer: String, path: String)
        fun onImageReceived(peer: String, path: String)
        fun onImageText(peer: String, text: String)
        fun onContactsChanged()
        fun onGroupMessage(groupId: String)
        fun onLocalAddress(addr: String)
        /**
         * A peer presented an unknown or changed key. The handshake has
         * already been refused; answer with [nativeTrustRespond] and the
         * engine pins the key and dials the peer again on accept.
         */
        fun onTrustPrompt(kind: String, peer: String, newKey: String, oldKey: String)
        /**
         * A peer offers a file. Bytes are spooled to a `.pending` file in the
         * app-private downloads directory until [nativeFileRespond] answers;
         * nothing is named, acknowledged or shown before that.
         */
        fun onFileOffer(peer: String, name: String, size: Long)
        fun onStarted()
        fun onStartFailed(message: String)
    }

    @JvmStatic external fun nativeInitKeyring()
    @JvmStatic external fun nativeSetListener(listener: Listener?)
    @JvmStatic external fun nativeListProfiles(appRoot: String): String
    /** JSON `{ok, address}` or `{ok:false, code}` (`needs_password`, `wrong_password`, …). */
    @JvmStatic external fun nativeProfileAddress(appRoot: String, profile: String, password: String): String
    @JvmStatic external fun nativeRenameProfile(appRoot: String, from: String, to: String): String
    @JvmStatic external fun nativeDeleteProfile(appRoot: String, profile: String): String
    @JvmStatic external fun nativePrepareRouter(
        dataDir: String,
        host: String,
        samPort: Int,
        httpProxy: Int,
        socksProxy: Int,
        controlHttp: Int,
    ): String
    @JvmStatic external fun nativeStart(
        appRoot: String,
        profile: String,
        samHost: String,
        samPort: Int,
        maxMessages: Int,
        maxAgeDays: Int,
        tunnelLength: Int,
        filesDir: String,
        imagesDir: String,
        profilePassword: String,
    ): String
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeTrustRespond(peer: String, keyHex: String, accept: Boolean)
    /** Returns false when the offer is no longer pending (peer dropped, newer offer). */
    @JvmStatic external fun nativeFileRespond(peer: String, accept: Boolean): Boolean
    /** Saves a direct chat in the contact book. Returns the canonical address, or empty if it is not a destination. */
    @JvmStatic external fun nativeRememberPeer(peer: String): String
    @JvmStatic external fun nativeConnectPeer(peer: String): Boolean
    @JvmStatic external fun nativeDisconnectPeer(peer: String)
    @JvmStatic external fun nativeSendText(peer: String, text: String): String
    @JvmStatic external fun nativeSendFile(peer: String, path: String, image: Boolean): Boolean
    @JvmStatic external fun nativePollBlindbox(): Int
    @JvmStatic external fun nativeSnapshot(): String
    @JvmStatic external fun nativeHistory(peer: String): String
    @JvmStatic external fun nativeGroupHistory(groupId: String): String
    @JvmStatic external fun nativeSetPeerProfile(addr: String, name: String, note: String)
    @JvmStatic external fun nativeRemovePeer(addr: String)
    @JvmStatic external fun nativeForgetPin(addr: String)
    @JvmStatic external fun nativeClearHistory(addr: String)
    @JvmStatic external fun nativeCreateGroup(title: String, membersJson: String): String
    @JvmStatic external fun nativeUpdateGroup(id: String, title: String, membersJson: String): String
    @JvmStatic external fun nativeDeleteGroup(id: String): Boolean
    @JvmStatic external fun nativeJoinGroup(token: String): String
    @JvmStatic external fun nativeSendGroupText(id: String, text: String)
    @JvmStatic external fun nativeEncodeInvite(id: String): String
    @JvmStatic external fun nativeGroupTopology(id: String): String
    @JvmStatic external fun nativeBackup(
        op: String,
        appRoot: String,
        profile: String,
        path: String,
        passphrase: String,
        includeHistory: Boolean,
    ): String
    @JvmStatic external fun nativeComposeDrafts(save: Boolean, json: String): String
    @JvmStatic external fun nativeSaveReplicas(json: String)
    @JvmStatic external fun nativeSetRetention(maxMessages: Int, maxAgeDays: Int)
    /** Empty string removes the password. Returns an error message, or empty on success. */
    @JvmStatic external fun nativeSetProfilePassword(password: String): String
}
