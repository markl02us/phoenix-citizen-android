package com.phoenix.citizen.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeUtils {

    /** Current UTC instant in ISO-8601 — what the backend expects in `ts_utc`. */
    fun nowUtcIso(): String = Instant.now().toString()

    /**
     * Convert an epoch-millis timestamp (e.g. [android.location.Location.getTime])
     * to a UTC ISO-8601 string. Returns null for a non-positive (absent) time so
     * callers can omit the field rather than ship a 1970 sentinel.
     */
    fun epochMillisToUtcIso(epochMillis: Long): String? =
        if (epochMillis > 0L) Instant.ofEpochMilli(epochMillis).toString() else null

    /** Format an ISO instant for display in the user's locale + timezone. */
    fun formatLocal(iso: String?, locale: Locale = Locale.getDefault()): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            val instant = Instant.parse(iso)
            val ldt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", locale))
        } catch (_: Throwable) { iso }
    }
}
