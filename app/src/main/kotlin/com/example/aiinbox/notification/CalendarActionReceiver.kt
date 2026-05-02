package com.example.aiinbox.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.example.aiinbox.calendar.CalendarIntentBuilder
import com.example.aiinbox.data.repository.InboxRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CalendarActionReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: InboxRepository

    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val item = repository.getById(itemId)
                val event = item?.event
                if (item != null && event != null) {
                    val calIntent = CalendarIntentBuilder.build(
                        event = event,
                        summary = item.summary,
                        originalTextSnippet = item.originalText.take(500),
                    )
                    // CalendarIntentBuilder.build already adds FLAG_ACTIVITY_NEW_TASK,
                    // which is required when starting an Activity from a BroadcastReceiver.
                    context.startActivity(calIntent)
                }
                if (notifId >= 0) NotificationManagerCompat.from(context).cancel(notifId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.example.aiinbox.ADD_TO_CALENDAR"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_NOTIF_ID = "notif_id"
    }
}
