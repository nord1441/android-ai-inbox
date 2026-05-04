package com.example.aiinbox.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.data.storage.EncryptedImageStore
import com.example.aiinbox.llm.ContentHintDetector
import com.example.aiinbox.llm.LlmServiceClient
import com.example.aiinbox.llm.ModelManager
import com.example.aiinbox.notification.NotificationHelper
import com.example.aiinbox.ocr.OcrEngine
import com.example.aiinbox.sync.SyncCoordinator

class TestSummarizeWorkerFactory(
    private val repo: InboxRepository,
    private val client: LlmServiceClient,
    private val modelManager: ModelManager,
    private val hintDetector: ContentHintDetector,
    private val notifier: NotificationHelper,
    private val ocr: OcrEngine,
    private val imageStore: EncryptedImageStore,
    private val syncCoordinator: SyncCoordinator,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        SummarizeWorker::class.java.name ->
            SummarizeWorker(
                appContext, workerParameters, repo, client, modelManager,
                hintDetector, notifier, ocr, imageStore, syncCoordinator,
            )
        else -> null
    }
}
