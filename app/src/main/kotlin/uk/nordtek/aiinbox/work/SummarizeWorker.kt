package uk.nordtek.aiinbox.work

import android.content.Context
import android.graphics.BitmapFactory
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import uk.nordtek.aiinbox.data.repository.InboxRepository
import uk.nordtek.aiinbox.data.storage.EncryptedImageStore
import uk.nordtek.aiinbox.llm.ContentHintDetector
import uk.nordtek.aiinbox.llm.LlmServiceClient
import uk.nordtek.aiinbox.llm.ModelManager
import uk.nordtek.aiinbox.notification.NotificationHelper
import uk.nordtek.aiinbox.ocr.OcrEngine
import uk.nordtek.aiinbox.sync.FsSyncCoordinator
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
    private val ocr: OcrEngine,
    private val imageStore: EncryptedImageStore,
    private val syncCoordinator: FsSyncCoordinator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return Result.failure()
        val full = repository.getItemWithAttachments(itemId) ?: return Result.failure()
        val item = full.item
        val attachments = full.attachments.sortedBy { it.ordering }

        android.util.Log.i(
            TAG,
            "doWork start. itemId=$itemId attempt=$runAttemptCount " +
                "textLen=${item.originalText?.length ?: 0} attachments=${attachments.size}",
        )

        val variant = modelManager.currentVariant() ?: run {
            android.util.Log.w(TAG, "No model present, returning Result.retry()")
            return Result.retry()
        }

        repository.markProcessing(itemId)

        // === 1) OCR 段（直列） ===
        for (att in attachments) {
            if (att.ocrText != null) continue  // 再要約時に既存OCRはスキップ
            try {
                val bytes = imageStore.read(att.encryptedFilename).use { it.readBytes() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp == null) {
                    android.util.Log.w(TAG, "decodeByteArray returned null for att=${att.id}")
                    repository.updateAttachmentOcr(att.id, "")
                    continue
                }
                val text = try {
                    ocr.recognize(bmp)
                } finally {
                    bmp.recycle()
                }
                repository.updateAttachmentOcr(att.id, text)
                android.util.Log.i(TAG, "OCR done att=${att.id} chars=${text.length}")
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "OCR failed for att=${att.id}", t)
                // null のまま残す（次回再要約時に再試行）
            }
        }

        // === 2) OCR 結果込みで再取得 ===
        val refreshed = repository.getItemWithAttachments(itemId) ?: return Result.failure()
        val refreshedAtts = refreshed.attachments.sortedBy { it.ordering }
        val joined = JoinedTextBuilder.build(refreshed.item.originalText, refreshedAtts)

        // === 3) OCR + 本文が両方空 → LLM スキップ、placeholder で COMPLETED ===
        if (joined.isBlank()) {
            android.util.Log.i(TAG, "Empty content, completing with placeholder")
            repository.applyPlaceholderResult(itemId, attachments.size)
            repository.getById(itemId)?.let { notifier.showCompletion(it) }
            syncCoordinator.requestImmediateSync()
            return Result.success()
        }

        // === 4) LLM 投入 ===
        val hint = hintDetector.detect(
            text = refreshed.item.originalText.orEmpty(),
            attachmentKinds = refreshedAtts.map { it.kind },
        )
        return try {
            android.util.Log.i(TAG, "Submitting to LlmServiceClient (hint=$hint, joinedLen=${joined.length})…")
            val r = client.submit(joined, hint, variant)
            r.fold(
                onSuccess = { res ->
                    android.util.Log.i(TAG, "Summarize success. summary=${res.summary?.take(40)}")
                    repository.applySummarizeResult(itemId, res)
                    repository.getById(itemId)?.let { notifier.showCompletion(it) }
                    syncCoordinator.requestImmediateSync()
                    Result.success()
                },
                onFailure = { t ->
                    android.util.Log.e(TAG, "Summarize failed", t)
                    if (runAttemptCount < MAX_RETRIES) Result.retry()
                    else {
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
