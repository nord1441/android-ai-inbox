package com.example.aiinbox.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

object NotificationChannels {
    const val CHANNEL_SUMMARY_COMPLETE = "summary_complete"
    const val CHANNEL_EVENT_DETECTED = "event_detected"
    const val CHANNEL_DOWNLOAD = "model_download"
    const val CHANNEL_SCREENSHOT_CAPTURE = "screenshot_capture"

    fun ensureCreated(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SUMMARY_COMPLETE,
                context.getString(com.example.aiinbox.R.string.notification_channel_summary_complete),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENT_DETECTED,
                context.getString(com.example.aiinbox.R.string.notification_channel_event_detected),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOAD,
                context.getString(com.example.aiinbox.R.string.notification_channel_download),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCREENSHOT_CAPTURE,
                "スクショ撮影中",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "スクリーンショット取り込み中の Foreground Service 通知"
            }
        )
    }
}
