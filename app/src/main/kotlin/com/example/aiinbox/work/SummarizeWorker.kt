package com.example.aiinbox.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.llm.ContentHintDetector
import com.example.aiinbox.llm.LlmServiceClient
import com.example.aiinbox.llm.ModelManager
import com.example.aiinbox.notification.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SummarizeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: InboxRepository,
    private val client: LlmServiceClient,
    private val modelManager: ModelManager,
    private val hintDetector: ContentHintDetector,
    private val notifier: NotificationHelper,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return Result.failure()
        val item = repository.getById(itemId) ?: return Result.failure()

        android.util.Log.i(TAG, "doWork start. itemId=$itemId attempt=$runAttemptCount textLen=${item.originalText?.length ?: 0}")

        // モデルが無ければ「準備待ち」状態のまま戻す（DLが完了したら別Workerが回収）
        val variant = modelManager.currentVariant() ?: run {
            android.util.Log.w(TAG, "No model present, returning Result.retry()")
            return Result.retry()
        }
        android.util.Log.i(TAG, "Using variant=$variant")

        repository.markProcessing(itemId)
        return try {
            val hint = hintDetector.detect(item.originalText.orEmpty())
            android.util.Log.i(TAG, "Submitting to LlmServiceClient (hint=$hint)…")
            val r = client.submit(item.originalText.orEmpty(), hint, variant)
            r.fold(
                onSuccess = { res ->
                    android.util.Log.i(TAG, "Summarize success. summary=${res.summary?.take(40)}")
                    repository.applySummarizeResult(itemId, res)
                    repository.getById(itemId)?.let { notifier.showCompletion(it) }
                    Result.success()
                },
                onFailure = { t ->
                    android.util.Log.e(TAG, "Summarize failed (Result.failure from client)", t)
                    if (runAttemptCount < MAX_RETRIES) {
                        Result.retry()
                    } else {
                        repository.markFailed(itemId, t.message ?: t::class.simpleName ?: "unknown")
                        Result.failure()
                    }
                }
            )
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "doWork threw", t)
            if (runAttemptCount < MAX_RETRIES) Result.retry()
            else {
                repository.markFailed(itemId, t.message ?: "unknown")
                Result.failure()
            }
        }
    }

    companion object {
        private const val TAG = "SummarizeWorker"
        const val KEY_ITEM_ID = "item_id"
        private const val MAX_RETRIES = 1
    }
}
