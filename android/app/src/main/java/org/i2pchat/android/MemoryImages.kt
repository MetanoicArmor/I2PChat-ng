package org.i2pchat.android

/**
 * Received pictures that exist only in RAM, for "Do not keep files".
 *
 * The engine hands such a picture over as bytes and writes nothing; this is
 * the one place those bytes live. They are gone when the process ends, and
 * the oldest are dropped once [maxTotalBytes] is exceeded: the chat then
 * shows the picture's name instead of the picture.
 */
class MemoryImageStore(private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES) {
    // Insertion order is arrival order, which is the eviction order.
    private val items = LinkedHashMap<String, ByteArray>()
    private var total = 0L

    /** Stores a picture and returns its key. A picture over the limit on its own is not kept. */
    @Synchronized
    fun put(peer: String, name: String, data: ByteArray): String {
        val key = keyOf(peer, name)
        items.remove(key)?.let { total -= it.size }
        if (data.size > maxTotalBytes) return key
        items[key] = data
        total += data.size
        val oldest = items.entries.iterator()
        while (total > maxTotalBytes && oldest.hasNext()) {
            val entry = oldest.next()
            if (entry.key == key) continue
            total -= entry.value.size
            oldest.remove()
        }
        return key
    }

    @Synchronized
    fun get(key: String): ByteArray? = items[key]

    @Synchronized
    fun contains(key: String): Boolean = items.containsKey(key)

    /** Drops every picture received from `peer`, for a cleared history or a removed contact. */
    @Synchronized
    fun removePeer(peer: String) {
        val prefix = "$peer$SEPARATOR"
        val each = items.entries.iterator()
        while (each.hasNext()) {
            val entry = each.next()
            if (entry.key.startsWith(prefix)) {
                total -= entry.value.size
                each.remove()
            }
        }
    }

    @Synchronized
    fun clear() {
        items.clear()
        total = 0
    }

    @get:Synchronized
    val totalBytes: Long get() = total

    companion object {
        /** A received picture is at most 5 MB, so this holds a dozen of the largest. */
        const val DEFAULT_MAX_TOTAL_BYTES = 64L * 1024 * 1024

        // Not a character of an I2P address or of a generated picture name.
        private const val SEPARATOR = '\n'

        /**
         * The peer is part of the key: a name typed by one peer cannot bring
         * up a picture that another peer sent.
         */
        fun keyOf(peer: String, name: String): String = "$peer$SEPARATOR$name"
    }
}

/** The store of the running app; the engine is process-wide too. */
val MemoryImages = MemoryImageStore()

/**
 * The key of the in-memory picture behind an `[image] <name>` history entry of
 * the direct chat with `peer`, or empty when that entry is not such a picture:
 * it names a file path, or the bytes are no longer (or were never) held.
 */
fun resolveMemoryImage(text: String, peer: String, store: MemoryImageStore = MemoryImages): String {
    if (peer.isBlank() || !text.startsWith("[image] ")) return ""
    val name = text.removePrefix("[image] ").trim()
    if (name.isBlank() || name.contains('/') || name.contains('\\')) return ""
    val key = MemoryImageStore.keyOf(peer, name)
    return if (store.contains(key)) key else ""
}
