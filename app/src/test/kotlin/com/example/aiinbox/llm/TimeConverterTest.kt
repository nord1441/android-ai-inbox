package com.example.aiinbox.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId

class TimeConverterTest {
    private val tz = ZoneId.of("Asia/Tokyo")

    @Test
    fun `parses ISO8601 datetime with offset`() {
        val r = TimeConverter.parseToMillis("2026-05-10T14:30:00+09:00", tz)!!
        // 2026-05-10T14:30:00+09:00 = 2026-05-10T05:30:00Z
        assertThat(r.millis).isEqualTo(1778391000000L)
        assertThat(r.allDay).isFalse()
    }

    @Test
    fun `parses ISO8601 date only as start of day in given zone`() {
        val r = TimeConverter.parseToMillis("2026-05-10", tz)!!
        // 2026-05-10 00:00 JST = 2026-05-09T15:00:00Z
        assertThat(r.allDay).isTrue()
        assertThat(r.millis).isEqualTo(1778338800000L)
    }

    @Test
    fun `parses ISO8601 datetime without offset using given zone`() {
        val r = TimeConverter.parseToMillis("2026-05-10T14:30", tz)!!
        // 2026-05-10T14:30 JST = 2026-05-10T05:30:00Z
        assertThat(r.allDay).isFalse()
        assertThat(r.millis).isEqualTo(1778391000000L)
    }

    @Test
    fun `null input returns null`() {
        assertThat(TimeConverter.parseToMillis(null, tz)).isNull()
    }

    @Test
    fun `invalid string returns null`() {
        assertThat(TimeConverter.parseToMillis("not a date", tz)).isNull()
    }

    @Test
    fun `formats millis back to ISO8601 with offset`() {
        // 1778391000000 = 2026-05-10T05:30:00Z = 2026-05-10T14:30:00+09:00
        val s = TimeConverter.formatFromMillis(1778391000000L, tz)
        assertThat(s).isEqualTo("2026-05-10T14:30:00+09:00")
    }
}
