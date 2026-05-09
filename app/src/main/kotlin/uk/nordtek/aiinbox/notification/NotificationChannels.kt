package uk.nordtek.aiinbox.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

object NotificationChannels {
    const val CHANNEL_SUMMARY_COMPLETE = "summary_complete"
    const val CHANNEL_EVENT_DETECTED = "event_detected"
    const val CHANNEL_DOWNLOAD = "model_download"

    fun ensureCreated(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SUMMARY_COMPLETE,
                context.getString(uk.nordtek.aiinbox.R.string.notification_channel_summary_complete),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENT_DETECTED,
                context.getString(uk.nordtek.aiinbox.R.string.notification_channel_event_detected),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOAD,
                context.getString(uk.nordtek.aiinbox.R.string.notification_channel_download),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }
}
