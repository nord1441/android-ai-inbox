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

    fun showCompletion(item: InboxItem) {
        NotificationChannels.ensureCreated(context)
        val title = item.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.notification_summary_complete_title)
        val text = item.summary ?: ""

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_OPEN_ITEM_ID, item.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_SUMMARY_COMPLETE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        runCatching {
            NotificationManagerCompat.from(context).notify(item.id.hashCode(), builder.build())
        }
    }

    companion object {
        const val EXTRA_OPEN_ITEM_ID = "open_item_id"
    }
}
