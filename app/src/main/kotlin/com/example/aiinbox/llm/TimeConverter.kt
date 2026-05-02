package com.example.aiinbox.llm

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object TimeConverter {

    data class Parsed(val millis: Long, val allDay: Boolean)

    fun parseToMillis(iso: String?, zone: ZoneId): Parsed? {
        if (iso.isNullOrBlank()) return null
        return try {
            // 1. オフセット付き ISO8601 (e.g., 2026-05-10T14:30:00+09:00)
            val odt = OffsetDateTime.parse(iso)
            Parsed(odt.toInstant().toEpochMilli(), allDay = false)
        } catch (_: Exception) {
            try {
                // 2. オフセットなし datetime (e.g., 2026-05-10T14:30 / T14:30:00)
                val ldt = LocalDateTime.parse(iso)
                Parsed(ldt.atZone(zone).toInstant().toEpochMilli(), allDay = false)
            } catch (_: Exception) {
                try {
                    // 3. 日付のみ → 終日扱い
                    val ld = LocalDate.parse(iso)
                    Parsed(ld.atStartOfDay(zone).toInstant().toEpochMilli(), allDay = true)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun formatFromMillis(millis: Long, zone: ZoneId): String {
        val zdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone)
        // OffsetDateTimeとして +09:00 形式で出力
        return zdt.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
