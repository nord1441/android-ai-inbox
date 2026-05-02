package com.example.aiinbox

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.aiinbox.notification.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AiInboxApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Notification channels must exist before any Worker / Service tries
        // to post a foreground-service notification. Otherwise startForeground
        // fails with CannotPostForegroundServiceNotificationException.
        NotificationChannels.ensureCreated(this)
    }
}
