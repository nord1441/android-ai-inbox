package uk.nordtek.aiinbox

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import uk.nordtek.aiinbox.notification.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class AiInboxApplication : Application(), Configuration.Provider, coil.ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var imageLoader: coil.ImageLoader

    override fun newImageLoader(): coil.ImageLoader = imageLoader

    /**
     * Application-scoped CoroutineScope that lives as long as the process.
     * Use for fire-and-forget work that must outlive the calling Activity
     * (e.g. ShareReceiverActivity finishes synchronously but still needs to
     * persist the shared text and enqueue the Worker).
     */
    val applicationScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
