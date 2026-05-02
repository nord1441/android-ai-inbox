package com.example.aiinbox.calendar

import android.content.Intent
import android.provider.CalendarContract
import com.example.aiinbox.data.db.ExtractedEvent

object CalendarIntentBuilder {
    fun build(event: ExtractedEvent, summary: String?, originalTextSnippet: String?): Intent {
        val description = buildString {
            if (!summary.isNullOrBlank()) {
                append(summary)
                append("\n\n")
            }
            if (!originalTextSnippet.isNullOrBlank()) {
                append("[原文抜粋]\n")
                append(originalTextSnippet)
            }
        }
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, event.title)
            event.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            event.startMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
            event.endMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            if (event.endMillis == null) {
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            }
            if (description.isNotBlank()) {
                putExtra(CalendarContract.Events.DESCRIPTION, description)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent
    }
}
