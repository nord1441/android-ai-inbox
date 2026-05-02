package com.example.aiinbox.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.aiinbox.llm.ModelManager
import com.example.aiinbox.llm.ModelVariant
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelDownloadWorkerTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private lateinit var modelManager: TestableModelManager
    private val httpClient = OkHttpClient()

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        server.start()
        modelManager = TestableModelManager(ctx, urlOverride = server.url("/model.task").toString())
        modelManager.deleteModel(ModelVariant.GEMMA_4_E2B)
    }

    @After
    fun teardown() { server.shutdown() }

    @Test
    fun `downloads file and writes to model path`() = runBlocking {
        val payload = ByteArray(2048) { (it % 256).toByte() }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Length", payload.size.toString())
                .setBody(Buffer().write(payload))
        )

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(ctx)
            .setInputData(Data.Builder().putString(ModelDownloadWorker.KEY_VARIANT, "GEMMA_4_E2B").build())
            .setWorkerFactory(TestModelDownloadWorkerFactory(modelManager, httpClient))
            .build()

        val result = worker.doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(modelManager.isModelPresent(ModelVariant.GEMMA_4_E2B)).isTrue()
        assertThat(modelManager.modelFilePath(ModelVariant.GEMMA_4_E2B).readBytes()).isEqualTo(payload)
    }
}

/** URLをoverrideできるテスト用ModelManager */
class TestableModelManager(ctx: Context, val urlOverride: String) :
    ModelManager(ctx) {
    override fun downloadUrl(variant: ModelVariant): String = urlOverride
}
