package com.example.aiinbox.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.llm.ContentHintDetector
import com.example.aiinbox.llm.LlmEngine
import com.example.aiinbox.notification.NotificationHelper

class TestSummarizeWorkerFactory(
    private val repo: InboxRepository,
    private val engine: LlmEngine,
    private val hintDetector: ContentHintDetector,
    private val notifier: NotificationHelper,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        return when (workerClassName) {
            SummarizeWorker::class.java.name ->
                SummarizeWorker(appContext, workerParameters, repo, engine, hintDetector, notifier)
            else -> null
        }
    }
}
