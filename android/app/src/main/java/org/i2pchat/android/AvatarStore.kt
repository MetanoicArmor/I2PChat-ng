package org.i2pchat.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Per-profile avatar previews + delivery ledger for the Android UI / push path.
 *
 * Layout under `profiles/<name>/avatars/`:
 * - `preview/self.jpg` — own avatar image
 * - `preview/<peer_sha>.jpg` — peer avatar image
 * - `ledger.json` — `{own_sha256, delivered:{peer:sha}, need_request:[peer]}`
 *
 * Wire filename matches the desktop: `.i2pchat-avatar.jpg`.
 */
object AvatarStore {
    const val WIRE_FILENAME = ".i2pchat-avatar.jpg"
    private const val MAX_BYTES = 256 * 1024

    fun isWireFilename(name: String): Boolean {
        val base = File(name).name
        return base == WIRE_FILENAME || base.startsWith(".i2pchat-avatar.")
    }

    fun profileAvatarsDir(appRoot: File, profile: String): File =
        File(appRoot, "profiles/$profile/avatars").also { it.mkdirs() }

    fun previewDir(appRoot: File, profile: String): File =
        File(profileAvatarsDir(appRoot, profile), "preview").also { it.mkdirs() }

    fun ownPreview(appRoot: File, profile: String): File =
        File(previewDir(appRoot, profile), "self.jpg")

    fun peerPreview(appRoot: File, profile: String, peerAddr: String): File? {
        val key = normalizePeer(peerAddr) ?: return null
        return File(previewDir(appRoot, profile), "${sha256Hex(key.toByteArray()).take(32)}.jpg")
    }

    fun setOwnAvatar(appRoot: File, profile: String, bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "empty avatar" }
        require(bytes.size <= MAX_BYTES) { "avatar larger than $MAX_BYTES bytes" }
        val digest = sha256Hex(bytes)
        ownPreview(appRoot, profile).writeBytes(bytes)
        val ledger = loadLedger(appRoot, profile)
        ledger.put("own_sha256", digest)
        ledger.put("delivered", JSONObject())
        saveLedger(appRoot, profile, ledger)
        return digest
    }

    fun setPeerAvatar(appRoot: File, profile: String, peerAddr: String, bytes: ByteArray): String {
        require(bytes.isNotEmpty()) { "empty avatar" }
        require(bytes.size <= MAX_BYTES) { "avatar larger than $MAX_BYTES bytes" }
        val key = normalizePeer(peerAddr) ?: throw IllegalArgumentException("invalid peer")
        val digest = sha256Hex(bytes)
        val dest = peerPreview(appRoot, profile, key) ?: throw IllegalArgumentException("invalid peer")
        dest.writeBytes(bytes)
        val ledger = loadLedger(appRoot, profile)
        val need = ledger.optJSONArray("need_request") ?: JSONArray()
        val filtered = JSONArray()
        for (i in 0 until need.length()) {
            if (need.optString(i) != key) filtered.put(need.optString(i))
        }
        ledger.put("need_request", filtered)
        saveLedger(appRoot, profile, ledger)
        return digest
    }

    fun needsOwnPush(appRoot: File, profile: String, peerAddr: String): Boolean {
        val key = normalizePeer(peerAddr) ?: return false
        val ledger = loadLedger(appRoot, profile)
        val own = ledger.optString("own_sha256")
        if (own.isBlank() || !ownPreview(appRoot, profile).isFile) return false
        val delivered = ledger.optJSONObject("delivered") ?: JSONObject()
        return delivered.optString(key) != own
    }

    fun markDelivered(appRoot: File, profile: String, peerAddr: String) {
        val key = normalizePeer(peerAddr) ?: return
        val ledger = loadLedger(appRoot, profile)
        val own = ledger.optString("own_sha256")
        if (own.isBlank()) return
        val delivered = ledger.optJSONObject("delivered") ?: JSONObject()
        delivered.put(key, own)
        ledger.put("delivered", delivered)
        saveLedger(appRoot, profile, ledger)
    }

    fun needsRequest(appRoot: File, profile: String, peerAddr: String): Boolean {
        val key = normalizePeer(peerAddr) ?: return false
        val need = loadLedger(appRoot, profile).optJSONArray("need_request") ?: return false
        for (i in 0 until need.length()) {
            if (need.optString(i) == key) return true
        }
        return false
    }

    fun markNeedRequestAll(appRoot: File, profile: String, peers: List<String>) {
        previewDir(appRoot, profile).listFiles()?.forEach { f ->
            if (f.name != "self.jpg") f.delete()
        }
        val need = JSONArray()
        val seen = mutableSetOf<String>()
        for (peer in peers) {
            val key = normalizePeer(peer) ?: continue
            if (seen.add(key)) need.put(key)
        }
        val ledger = loadLedger(appRoot, profile)
        ledger.put("need_request", need)
        saveLedger(appRoot, profile, ledger)
    }

    fun clearNeedRequest(appRoot: File, profile: String, peerAddr: String) {
        val key = normalizePeer(peerAddr) ?: return
        val ledger = loadLedger(appRoot, profile)
        val need = ledger.optJSONArray("need_request") ?: return
        val filtered = JSONArray()
        for (i in 0 until need.length()) {
            if (need.optString(i) != key) filtered.put(need.optString(i))
        }
        ledger.put("need_request", filtered)
        saveLedger(appRoot, profile, ledger)
    }

    private fun ledgerFile(appRoot: File, profile: String): File =
        File(profileAvatarsDir(appRoot, profile), "ledger.json")

    private fun loadLedger(appRoot: File, profile: String): JSONObject {
        val file = ledgerFile(appRoot, profile)
        if (!file.isFile) {
            return JSONObject()
                .put("own_sha256", "")
                .put("delivered", JSONObject())
                .put("need_request", JSONArray())
        }
        return runCatching { JSONObject(file.readText()) }.getOrElse {
            JSONObject()
                .put("own_sha256", "")
                .put("delivered", JSONObject())
                .put("need_request", JSONArray())
        }
    }

    private fun saveLedger(appRoot: File, profile: String, ledger: JSONObject) {
        ledgerFile(appRoot, profile).writeText(ledger.toString())
    }

    private fun normalizePeer(addr: String): String? {
        val trimmed = addr.trim().lowercase()
        if (trimmed.isEmpty()) return null
        // Keep full destination string; hashes are derived from it.
        return trimmed
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
