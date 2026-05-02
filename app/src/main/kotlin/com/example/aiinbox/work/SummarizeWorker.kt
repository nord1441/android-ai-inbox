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

        // モデルが無ければ「準備待ち」状態のまま戻す（DLが完了したら別Workerが回収）
        val variant = modelManager.currentVariant() ?: run {
            return Result.retry()  // モデルDL中などは後で再試行
        }

        repository.markProcessing(itemId)
        return try {
            val hint = hintDetector.detect(item.originalText)
            val r = client.submit(item.originalText, hint, variant)
            r.fold(
                onSuccess = { res ->
                    repository.applySummarizeResult(itemId, res)
                    repository.getById(itemId)?.let { notifier.showCompletion(it) }
                    Result.success()
                },
                onFailure = { t ->
                    if (runAttemptCount < MAX_RETRIES) {
                        Result.retry()
                    } else {
                        repository.markFailed(itemId, t.message ?: t::class.simpleName ?: "unknown")
                        Result.failure()
                    }
                }
            )
        } catch (t: Throwable) {
            if (runAttemptCount < MAX_RETRIES) Result.retry()
            else {
                repository.markFailed(itemId, t.message ?: "unknown")
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_ITEM_ID = "item_id"
        private const val MAX_RETRIES = 1
    }
}
