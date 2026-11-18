package org.i2pchat.android

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Display helpers for history timestamps.
 *
 * Storage and any peer-facing wire fields stay ISO-8601 UTC; only the UI clock
 * is converted to the device timezone (same idea as C++ `format_clock`).
 */
object TimeFormat {
    private val localClock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** `HH:mm:ss` in the device timezone, or empty when [isoTs] cannot be parsed. */
    fun formatMessageClock(isoTs: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val instant = parseInstant(isoTs.trim()) ?: return ""
        return localClock.format(instant.atZone(zone))
    }

    private fun parseInstant(text: String): Instant? {
        if (text.isEmpty()) return null
        val normalized = text.replace(' ', 'T')
        try {
            return OffsetDateTime.parse(normalized).toInstant()
        } catch (_: DateTimeParseException) {
        }
        try {
            return Instant.parse(normalized)
        } catch (_: DateTimeParseException) {
        }
        // History sometimes carries a naive UTC wall-clock (no Z / offset).
        return try {
            LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
