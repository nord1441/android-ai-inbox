package uk.nordtek.aiinbox.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import uk.nordtek.aiinbox.data.crypto.KeystorePassphraseProvider
import uk.nordtek.aiinbox.data.db.AppDatabase
import uk.nordtek.aiinbox.data.db.buildEncryptedDatabase
import uk.nordtek.aiinbox.data.repository.InboxRepository
import uk.nordtek.aiinbox.llm.ContentHintDetector
import uk.nordtek.aiinbox.llm.LlmServiceClient
import uk.nordtek.aiinbox.llm.ModelManager
import uk.nordtek.aiinbox.notification.NotificationHelper
import uk.nordtek.aiinbox.ocr.FakeOcrEngine
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
    private lateinit var attachmentDir: java.io.File

    private lateinit var store: uk.nordtek.aiinbox.data.storage.EncryptedImageStore

    @Before
    fun setup() {
        ctx.deleteDatabase("inbox.db")
        db = buildEncryptedDatabase(ctx, KeystorePassphraseProvider(ctx))
        attachmentDir = java.io.File(ctx.cacheDir, "attach-${this::class.java.simpleName}").apply { deleteRecursively(); mkdirs() }
        store = uk.nordtek.aiinbox.data.storage.EncryptedImageStore(ctx, attachmentDir)
        repo = InboxRepository(db.inboxDao(), db.attachmentDao(), store)

        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .build()
        )
    }

    @After fun teardown() { db.close(); ctx.deleteDatabase("inbox.db"); attachmentDir.deleteRecursively() }

    @Test
    fun `worker returns retry when no model is present`() = runBlocking {
        val id = repo.createPendingItem("テストの本文", null, "test")

        val client = LlmServiceClient(ctx)
        val modelManager = ModelManager(ctx)
        // Ensure no model is present — currentVariant() should return null.
        modelManager.deleteModel(uk.nordtek.aiinbox.llm.ModelVariant.GEMMA_4_E2B)
        modelManager.deleteModel(uk.nordtek.aiinbox.llm.ModelVariant.GEMMA_4_E2B)

        val worker = TestListenableWorkerBuilder<SummarizeWorker>(ctx)
            .setInputData(Data.Builder().putString(SummarizeWorker.KEY_ITEM_ID, id).build())
            .setWorkerFactory(
                TestSummarizeWorkerFactory(repo, client, modelManager, ContentHintDetector(), NotificationHelper(ctx), FakeOcrEngine(), store, uk.nordtek.aiinbox.sync.FsSyncCoordinator(ctx))
            )
            .build()

        val result = worker.doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }
}
