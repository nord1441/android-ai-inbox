package com.example.aiinbox.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.aiinbox.data.crypto.HfTokenStore
import com.example.aiinbox.llm.ModelManager
import okhttp3.OkHttpClient

class TestModelDownloadWorkerFactory(
    private val modelManager: ModelManager,
    private val http: OkHttpClient,
    private val hfTokenStore: HfTokenStore,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context, workerClassName: String, workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        ModelDownloadWorker::class.java.name ->
            ModelDownloadWorker(appContext, workerParameters, modelManager, http, hfTokenStore)
        else -> null
    }
}
