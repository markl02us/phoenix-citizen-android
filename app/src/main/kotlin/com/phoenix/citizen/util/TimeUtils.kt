package com.phoenix.citizen.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeUtils {

    /** Current UTC instant in ISO-8601 — what the backend expects in `ts_utc`. */
    fun nowUtcIso(): String = Instant.now().toString()

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
