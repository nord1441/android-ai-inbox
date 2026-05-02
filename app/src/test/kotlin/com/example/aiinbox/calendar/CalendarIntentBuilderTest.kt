package com.example.aiinbox.calendar

import android.content.Intent
import android.provider.CalendarContract
import com.example.aiinbox.data.db.ExtractedEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CalendarIntentBuilderTest {

    @Test
    fun `builds insert intent with prefilled extras`() {
        val event = ExtractedEvent(
            title = "ミーティング",
            startMillis = 1700000000000L,
            endMillis = 1700003600000L,
            location = "渋谷",
            confidence = 0.9f,
        )
        val intent = CalendarIntentBuilder.build(
            event = event,
            summary = "明日の打ち合わせ",
            originalTextSnippet = "原文の冒頭",
        )
        assertThat(intent.action).isEqualTo(Intent.ACTION_INSERT)
        assertThat(intent.data).isEqualTo(CalendarContract.Events.CONTENT_URI)
        assertThat(intent.getStringExtra(CalendarContract.Events.TITLE)).isEqualTo("ミーティング")
        assertThat(intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION)).isEqualTo("渋谷")
        assertThat(intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L))
            .isEqualTo(1700000000000L)
        assertThat(intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L))
            .isEqualTo(1700003600000L)
        val desc = intent.getStringExtra(CalendarContract.Events.DESCRIPTION) ?: ""
        assertThat(desc).contains("明日の打ち合わせ")
        assertThat(desc).contains("原文の冒頭")
    }

    @Test
    fun `marks all-day when endMillis is null`() {
        val event = ExtractedEvent(
            title = "終日",
            startMillis = 1700000000000L,
            endMillis = null,
            location = null,
            confidence = 0.7f,
        )
        val intent = CalendarIntentBuilder.build(event, summary = null, originalTextSnippet = null)
        assertThat(intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)).isTrue()
    }
}
