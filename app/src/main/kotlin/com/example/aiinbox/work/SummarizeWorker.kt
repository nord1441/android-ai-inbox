package com.example.aiinbox.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.llm.ContentHintDetector
import com.example.aiinbox.llm.LlmEngine
import com.example.aiinbox.llm.ModelVariant
import com.example.aiinbox.notification.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SummarizeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: InboxRepository,
    private val llmEngine: LlmEngine,
    private val hintDetector: ContentHintDetector,
    private val notifier: NotificationHelper,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return Result.failure()
        val item = repository.getById(itemId) ?: return Result.failure()

        repository.markProcessing(itemId)
        return try {
            llmEngine.ensureLoaded(ModelVariant.FAKE)
            val hint = hintDetector.detect(item.originalText)
            val result = llmEngine.summarize(item.originalText, hint)
            repository.applySummarizeResult(itemId, result)
            val updated = repository.getById(itemId)
            if (updated != null) notifier.showCompletion(updated)
            Result.success()
        } catch (t: Throwable) {
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                repository.markFailed(itemId, t.message ?: t::class.simpleName ?: "unknown")
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_ITEM_ID = "item_id"
        private const val MAX_RETRIES = 1
    }
}
