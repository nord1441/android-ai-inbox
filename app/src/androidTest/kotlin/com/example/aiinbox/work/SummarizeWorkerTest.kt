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
import com.example.aiinbox.data.crypto.KeystorePassphraseProvider
import com.example.aiinbox.data.db.AppDatabase
import com.example.aiinbox.data.db.buildEncryptedDatabase
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.llm.ContentHintDetector
import com.example.aiinbox.llm.LlmServiceClient
import com.example.aiinbox.llm.ModelManager
import com.example.aiinbox.notification.NotificationHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for SummarizeWorker post-Plan-2-T8 refactor.
 *
 * The full PENDING→COMPLETED end-to-end test was removed because the Worker now
 * routes through LlmInferenceService via LlmServiceClient. Reproducing that path
 * requires a live Service binding which is out of scope for an isolated worker test.
 *
 * The full pipeline is verified manually in P2-T13 (see ANDROID_TEST_EXECUTION_GUIDE).
 */
@RunWith(AndroidJUnit4::class)
class SummarizeWorkerTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: InboxRepository

    @Before
    fun setup() {
        ctx.deleteDatabase("inbox.db")
        db = buildEncryptedDatabase(ctx, KeystorePassphraseProvider(ctx))
        repo = InboxRepository(db.inboxDao())

        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .build()
        )
    }

    @After fun teardown() { db.close(); ctx.deleteDatabase("inbox.db") }

    @Test
    fun `worker returns retry when no model is present`() = runBlocking {
        val id = repo.createPendingItem("テストの本文", null, "test")

        val client = LlmServiceClient(ctx)
        val modelManager = ModelManager(ctx)
        // Ensure no model is present — currentVariant() should return null.
        modelManager.deleteModel(com.example.aiinbox.llm.ModelVariant.GEMMA_4_E2B)
        modelManager.deleteModel(com.example.aiinbox.llm.ModelVariant.GEMMA_4_E4B)

        val worker = TestListenableWorkerBuilder<SummarizeWorker>(ctx)
            .setInputData(Data.Builder().putString(SummarizeWorker.KEY_ITEM_ID, id).build())
            .setWorkerFactory(
                TestSummarizeWorkerFactory(repo, client, modelManager, ContentHintDetector(), NotificationHelper(ctx))
            )
            .build()

        val result = worker.doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }
}
