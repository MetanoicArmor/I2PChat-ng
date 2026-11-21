package org.i2pchat.android

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Validate and import a manual netDb ZIP when bundled i2pd reseed fails.
 *
 * Keep in sync with `i2pchat/router/netdb_import.py`: more than half of ZIP
 * file entries must be RouterInfo blobs published within 270 hours.
 */
object NetDbImport {
    const val MAX_AGE_HOURS = 270
    private const val MAX_AGE_MS = MAX_AGE_HOURS * 3600L * 1000L
    private const val IDENTITY_KEY_BYTES = 256 + 128
    private const val MIN_PUBLISHED_MS = 946_684_800_000L
    private const val MAX_PUBLISHED_MS = 4_102_444_800_000L

    data class Stats(
        val totalFiles: Int,
        val routerInfoFiles: Int,
        val freshRouterInfoFiles: Int,
    ) {
        val accepted: Boolean
            get() = totalFiles > 0 && freshRouterInfoFiles * 2 > totalFiles
    }

    class ImportException(message: String) : Exception(message)

    fun routerInfoPublishedMs(data: ByteArray): Long? {
        if (data.size < IDENTITY_KEY_BYTES + 3 + 8) return null
        var offset = IDENTITY_KEY_BYTES
        val certLen = ((data[offset + 1].toInt() and 0xff) shl 8) or (data[offset + 2].toInt() and 0xff)
        offset += 3 + certLen
        if (offset + 8 > data.size) return null
        var published = 0L
        for (i in 0 until 8) {
            published = (published shl 8) or (data[offset + i].toInt() and 0xff).toLong()
        }
        if (published < MIN_PUBLISHED_MS || published > MAX_PUBLISHED_MS) return null
        return published
    }

    fun isFresh(publishedMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val age = nowMs - publishedMs
        if (age < -3_600_000L) return false
        return age <= MAX_AGE_MS
    }

    fun inspect(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): Stats {
        var total = 0
        var ri = 0
        var fresh = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    if (entry.isDirectory) continue
                    val name = entry.name.replace('\\', '/')
                    if (name.isBlank() || name.endsWith('/')) continue
                    total += 1
                    val data = zip.readBytes()
                    val published = routerInfoPublishedMs(data) ?: continue
                    ri += 1
                    if (isFresh(published, nowMs)) fresh += 1
                } finally {
                    zip.closeEntry()
                }
            }
        }
        return Stats(total, ri, fresh)
    }

    fun validate(bytes: ByteArray, nowMs: Long = System.currentTimeMillis()): Stats {
        val stats = inspect(bytes, nowMs)
        if (stats.totalFiles <= 0) throw ImportException("The ZIP archive is empty.")
        if (!stats.accepted) {
            throw ImportException(
                "The archive must contain more than half RouterInfo files " +
                    "published within the last $MAX_AGE_HOURS hours " +
                    "(found ${stats.freshRouterInfoFiles} fresh of ${stats.totalFiles} files).",
            )
        }
        return stats
    }

    fun extractToNetDb(
        bytes: ByteArray,
        netDbDir: File,
        nowMs: Long = System.currentTimeMillis(),
    ): Stats {
        val stats = validate(bytes, nowMs)
        netDbDir.mkdirs()
        var written = 0
        var index = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    if (entry.isDirectory) continue
                    val name = entry.name.replace('\\', '/')
                    if (name.isBlank() || name.endsWith('/')) continue
                    val data = zip.readBytes()
                    if (routerInfoPublishedMs(data) == null) continue
                    val out = File(netDbDir, safeRiBasename(name, index))
                    out.writeBytes(data)
                    written += 1
                    index += 1
                } finally {
                    zip.closeEntry()
                }
            }
        }
        if (written <= 0) throw ImportException("No RouterInfo files could be written to netDb.")
        return stats
    }

    fun netDbDir(appRoot: File): File = File(appRoot, "router/netDb")

    private fun safeRiBasename(zipName: String, index: Int): String {
        var base = File(zipName.replace('\\', '/')).name
        if (base.isBlank() || base == "." || base == "..") {
            base = "routerInfo-import-$index.dat"
        }
        if (!base.lowercase().startsWith("routerinfo")) {
            base = "routerInfo-$base"
        }
        return base.replace("\u0000", "_")
    }
}
