package com.example.aiinbox.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.aiinbox.MainActivity
import com.example.aiinbox.R
import com.example.aiinbox.data.db.InboxItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val groupKey = "ai_inbox_group"

    fun showCompletion(item: InboxItem) {
        NotificationChannels.ensureCreated(context)
        val notifId = item.id.hashCode()
        val title = item.title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.notification_summary_complete_title)
        val text = item.summary ?: ""
        val hasEvent = item.event != null
        val channel = if (hasEvent)
            NotificationChannels.CHANNEL_EVENT_DETECTED
        else NotificationChannels.CHANNEL_SUMMARY_COMPLETE

        val contentPI = openItemPendingIntent(item.id, notifId)

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentPI)
            .setAutoCancel(true)
            .setGroup(groupKey)
            .setPriority(if (hasEvent) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)

        if (hasEvent) {
            val addPI = calendarActionPendingIntent(item.id, notifId)
            builder.addAction(
                android.R.drawable.ic_menu_my_calendar,
                context.getString(R.string.add_to_calendar),
                addPI,
            )
        }

        runCatching {
            val nm = NotificationManagerCompat.from(context)
            nm.notify(notifId, builder.build())
            // Group summary so multiple completions stack under one entry
            nm.notify(GROUP_SUMMARY_ID, buildGroupSummary())
        }
    }

    private fun openItemPendingIntent(itemId: String, notifId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_OPEN_ITEM_ID, itemId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun calendarActionPendingIntent(itemId: String, notifId: Int): PendingIntent {
        val intent = Intent(CalendarActionReceiver.ACTION).apply {
            // Explicit package so the implicit-action intent resolves only to our receiver
            // (Android 8+ disallows implicit broadcasts to system; explicit is required).
            setPackage(context.packageName)
            putExtra(CalendarActionReceiver.EXTRA_ITEM_ID, itemId)
            putExtra(CalendarActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        return PendingIntent.getBroadcast(
            context,
            notifId xor 0xCAFE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildGroupSummary() = NotificationCompat.Builder(
        context, NotificationChannels.CHANNEL_SUMMARY_COMPLETE
    )
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(context.getString(R.string.app_name))
        .setStyle(
            NotificationCompat.InboxStyle()
                .setSummaryText(context.getString(R.string.app_name))
        )
        .setGroup(groupKey)
        .setGroupSummary(true)
        .build()

    companion object {
        const val EXTRA_OPEN_ITEM_ID = "open_item_id"
        private const val GROUP_SUMMARY_ID = 0x0001
    }
}
