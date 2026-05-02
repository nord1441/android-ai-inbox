package com.example.aiinbox.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.aiinbox.R
import com.example.aiinbox.llm.ModelManager
import com.example.aiinbox.llm.ModelVariant
import com.example.aiinbox.notification.NotificationChannels
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val modelManager: ModelManager,
    private val httpClient: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // Defensive: AiInboxApplication.onCreate already creates channels, but this
        // ensures the channel exists even if the Worker runs before Application init
        // (rare but possible in some restart scenarios). createNotificationChannel
        // is idempotent.
        NotificationChannels.ensureCreated(applicationContext)
        val notif = androidx.core.app.NotificationCompat.Builder(
            applicationContext,
            NotificationChannels.CHANNEL_DOWNLOAD,
        )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.dl_notif_title))
            .setProgress(100, 0, true)
            .setOngoing(true)
            .build()

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    override suspend fun doWork(): Result {
        val variantName = inputData.getString(KEY_VARIANT) ?: return Result.failure()
        val variant = ModelVariant.valueOf(variantName)
        android.util.Log.i(TAG, "doWork start. variant=$variant attempt=$runAttemptCount url=${modelManager.downloadUrl(variant)}")

        return try {
            android.util.Log.i(TAG, "calling setForeground…")
            setForeground(getForegroundInfo())
            android.util.Log.i(TAG, "setForeground returned. starting downloadWithResume…")
            withContext(Dispatchers.IO) {
                downloadWithResume(variant)
            }
            android.util.Log.i(TAG, "downloadWithResume done. Result.success.")
            Result.success(Data.Builder().putString(KEY_VARIANT, variant.name).build())
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "doWork threw (attempt=$runAttemptCount)", t)
            if (runAttemptCount < MAX_RETRIES) Result.retry()
            else Result.failure(Data.Builder().putString(KEY_ERROR, t.message ?: "unknown").build())
        }
    }

    private suspend fun downloadWithResume(variant: ModelVariant) {
        val target: File = modelManager.modelFilePath(variant)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        val existing = if (tmp.exists()) tmp.length() else 0L
        android.util.Log.i(TAG, "target=${target.absolutePath} existing=$existing")

        val request = Request.Builder()
            .url(modelManager.downloadUrl(variant))
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        android.util.Log.i(TAG, "executing HTTP GET ${request.url}")
        httpClient.newCall(request).execute().use { response ->
            android.util.Log.i(TAG, "HTTP response code=${response.code} contentLength=${response.header("Content-Length")}")
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: error("empty body")
            val totalKnown = response.header("Content-Length")?.toLongOrNull()?.let { it + existing }
            val total = totalKnown ?: modelManager.expectedSizeBytes(variant)

            RandomAccessFile(tmp, "rw").use { raf ->
                raf.seek(existing)
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = existing
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        downloaded += n
                        if (downloaded % (1024 * 1024) < buf.size) {
                            setProgress(
                                Data.Builder()
                                    .putLong(KEY_DOWNLOADED, downloaded)
                                    .putLong(KEY_TOTAL, total)
                                    .build()
                            )
                        }
                    }
                }
            }
        }

        // tmp → target にrename
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "ModelDownloadWorker"
        const val KEY_VARIANT = "variant"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val NOTIF_ID = 0x10D1
        private const val MAX_RETRIES = 3
    }
}
