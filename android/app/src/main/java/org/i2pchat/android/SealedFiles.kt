package org.i2pchat.android

/**
 * Ciphertext paths for files received while "Do not keep files" is on.
 *
 * The bytes on flash are useless once the engine wipes its session key. This
 * map only lets the open chat find those bytes and ask the engine to decrypt
 * them. It is cleared when the chat stops.
 */
class SealedFileStore {
    data class Entry(val path: String, val displayName: String)

    private val items = HashMap<String, Entry>()

    @Synchronized
    fun put(peer: String, id: String, displayName: String, path: String) {
        items[key(peer, id)] = Entry(path, displayName)
    }

    @Synchronized
    fun get(peer: String, id: String): Entry? = items[key(peer, id)]

    @Synchronized
    fun contains(peer: String, id: String): Boolean = items.containsKey(key(peer, id))

    @Synchronized
    fun removePeer(peer: String) {
        val prefix = "$peer\n"
        items.keys.filter { it.startsWith(prefix) }.forEach { items.remove(it) }
    }

    @Synchronized
    fun clear() {
        items.clear()
    }

    private fun key(peer: String, id: String) = "$peer\n$id"
}

val SealedFiles = SealedFileStore()

data class SealedFileRef(val id: String, val displayName: String)

private val sealIdPattern = Regex("seal_[0-9a-f]{32}")

/**
 * `[file] seal_<32 hex> <display name>` from a sealed download.
 * A normal `[file] /path` is not this.
 */
fun parseSealedFileHistory(text: String): SealedFileRef? {
    if (!text.startsWith("[file] ")) return null
    val rest = text.removePrefix("[file] ").trim()
    if (rest.contains('/') || rest.contains('\\')) return null
    val space = rest.indexOf(' ')
    if (space <= 0) return null
    val id = rest.substring(0, space)
    if (!sealIdPattern.matches(id)) return null
    val name = rest.substring(space + 1).trim()
    if (name.isEmpty()) return null
    return SealedFileRef(id, name)
}
