package com.example.aiinbox.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.example.aiinbox.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import javax.inject.Inject

@AndroidEntryPoint
class LlmInferenceService : Service() {

    @Inject lateinit var llmEngine: LlmEngine
    @Inject lateinit var modelManager: ModelManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binder = LocalBinder()
    private val jobQueue = Channel<Job>(capacity = 64)

    data class Job(
        val text: String,
        val hint: ContentHint,
        val variant: ModelVariant,
        val deferred: CompletableDeferred<Result<SummarizeResult>>,
    )

    inner class LocalBinder : Binder() {
        fun submit(job: Job) {
            jobQueue.trySend(job)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIF_ID,
            buildNotification(getString(R.string.notification_llm_service_idle)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch { runQueueLoop() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        jobQueue.close()
        scope.launch { llmEngine.unload() }
        scope.cancel()
        super.onDestroy()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun runQueueLoop() {
        while (scope.isActive) {
            val job = receiveJobOrTimeout() ?: break  // timeout → break, unload, stopSelf
            try {
                updateNotif(getString(R.string.notification_llm_service_running))
                llmEngine.ensureLoaded(job.variant)
                val result = try {
                    llmEngine.summarize(job.text, job.hint)
                } catch (oom: OutOfMemoryError) {
                    val truncated = job.text.take(job.text.length / 2)
                    llmEngine.summarize(truncated, job.hint)
                }
                job.deferred.complete(Result.success(result))
            } catch (t: Throwable) {
                job.deferred.complete(Result.failure(t))
            } finally {
                updateNotif(getString(R.string.notification_llm_service_idle))
            }
        }
        // Loop exited via timeout — unload model and stop the service.
        llmEngine.unload()
        stopSelf()
    }

    /** Returns the next Job, or null if no job arrives within IDLE_TIMEOUT_MS. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun receiveJobOrTimeout(): Job? = select {
        jobQueue.onReceiveCatching { result -> result.getOrNull() }
        onTimeout(IDLE_TIMEOUT_MS) { null }
    }

    private fun createChannel() {
        val nm = getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_llm_service),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun updateNotif(text: String) {
        getSystemService<NotificationManager>()?.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "llm_service"
        private const val NOTIF_ID = 0x10A1
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L  // 5 minutes
    }
}
