package uk.nordtek.aiinbox.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import uk.nordtek.aiinbox.data.repository.InboxRepository
import uk.nordtek.aiinbox.data.storage.EncryptedImageStore
import uk.nordtek.aiinbox.llm.ContentHintDetector
import uk.nordtek.aiinbox.llm.LlmServiceClient
import uk.nordtek.aiinbox.llm.ModelManager
import uk.nordtek.aiinbox.notification.NotificationHelper
import uk.nordtek.aiinbox.ocr.OcrEngine
import uk.nordtek.aiinbox.sync.FsSyncCoordinator

class TestSummarizeWorkerFactory(
    private val repo: InboxRepository,
    private val client: LlmServiceClient,
    private val modelManager: ModelManager,
    private val hintDetector: ContentHintDetector,
    private val notifier: NotificationHelper,
    private val ocr: OcrEngine,
    private val imageStore: EncryptedImageStore,
    private val syncCoordinator: FsSyncCoordinator,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        SummarizeWorker::class.java.name ->
            SummarizeWorker(appContext, workerParameters, repo, client, modelManager, hintDetector, notifier, ocr, imageStore, syncCoordinator)
        else -> null
    }
}
