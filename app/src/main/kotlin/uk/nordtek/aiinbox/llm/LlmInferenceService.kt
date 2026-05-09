package uk.nordtek.aiinbox.llm

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
import uk.nordtek.aiinbox.R
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
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@AndroidEntryPoint
class LlmInferenceService : Service() {

    @Inject lateinit var llmEngine: LlmEngine
    @Inject lateinit var modelManager: ModelManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binder = LocalBinder()
    private val jobQueue = Channel<Job>(capacity = 64)
    private val currentJob = AtomicReference<Job?>(null)

    data class Job(
        val text: String,
        val hint: ContentHint,
        val variant: ModelVariant,
        val deferred: CompletableDeferred<Result<SummarizeResult>>,
    )

    inner class LocalBinder : Binder() {
        fun submit(job: Job) {
            val result = jobQueue.trySend(job)
            if (!result.isSuccess) {
                // Channel rejected (capacity exceeded or already closed). The caller is
                // suspended on `deferred.await()` — failing it explicitly prevents the
                // SummarizeWorker from hanging forever.
                val cause = result.exceptionOrNull()
                    ?: IllegalStateException("LlmInferenceService queue rejected job (full)")
                android.util.Log.w(TAG, "Job rejected by queue", cause)
                job.deferred.complete(Result.failure(cause))
            }
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

    /**
     * Called by the platform when this dataSync FGS hits its time limit (Android 14+).
     * Without this hook the service is killed silently and any in-flight job's
     * `deferred` is never completed — the awaiting Worker would hang forever.
     */
    override fun onTimeout(startId: Int) {
        android.util.Log.w(TAG, "Service.onTimeout fired — terminating in-flight jobs and stopping")
        terminateAllJobs(IllegalStateException("LlmInferenceService FGS timeout"))
        stopSelf(startId)
    }

    override fun onDestroy() {
        jobQueue.close()
        terminateAllJobs(IllegalStateException("LlmInferenceService destroyed"))
        scope.launch { runCatching { llmEngine.unload() } }
        scope.cancel()
        super.onDestroy()
    }

    private fun terminateAllJobs(cause: Throwable) {
        currentJob.getAndSet(null)?.deferred?.complete(Result.failure(cause))
        while (true) {
            val r = jobQueue.tryReceive()
            val j = r.getOrNull() ?: break
            j.deferred.complete(Result.failure(cause))
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun runQueueLoop() {
        while (scope.isActive) {
            val job = receiveJobOrTimeout() ?: break  // timeout → break, unload, stopSelf
            currentJob.set(job)
            try {
                android.util.Log.i(TAG, "Job received: variant=${job.variant} textLen=${job.text.length}")
                updateNotif(getString(R.string.notification_llm_service_running))
                android.util.Log.i(TAG, "ensureLoaded(${job.variant}) starting…")
                llmEngine.ensureLoaded(job.variant)
                android.util.Log.i(TAG, "ensureLoaded done. Starting summarize…")
                val result = withTimeout(LLM_PER_JOB_TIMEOUT_MS) {
                    try {
                        llmEngine.summarize(job.text, job.hint)
                    } catch (oom: OutOfMemoryError) {
                        android.util.Log.w(TAG, "OOM during summarize, retrying with halved context")
                        val truncated = job.text.take(job.text.length / 2)
                        llmEngine.summarize(truncated, job.hint)
                    }
                }
                android.util.Log.i(TAG, "summarize done. summary=${result.summary?.take(40)}")
                job.deferred.complete(Result.success(result))
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Job failed", t)
                job.deferred.complete(Result.failure(t))
            } finally {
                currentJob.compareAndSet(job, null)
                updateNotif(getString(R.string.notification_llm_service_idle))
            }
        }
        // Loop exited via timeout — unload model and stop the service.
        android.util.Log.i(TAG, "Idle timeout — unloading model and stopping service")
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
        private const val TAG = "LlmInferenceService"
        private const val CHANNEL_ID = "llm_service"
        private const val NOTIF_ID = 0x10A1
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L  // 5 minutes
        // Hard cap on a single summarize call. Without this a stuck native
        // inference (no cooperative cancellation) would block the queue and the
        // awaiting Worker indefinitely. 10 min is generous: even on slow CPU,
        // E2B finishes well under this; anything beyond is treated as failure.
        private const val LLM_PER_JOB_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
