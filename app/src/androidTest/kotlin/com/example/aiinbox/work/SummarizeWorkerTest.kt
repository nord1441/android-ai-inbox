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
import com.example.aiinbox.data.db.ItemStatus
import com.example.aiinbox.data.db.buildEncryptedDatabase
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.llm.ContentHintDetector
import com.example.aiinbox.llm.FakeLlmEngine
import com.example.aiinbox.notification.NotificationHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
    fun `worker transitions PENDING to COMPLETED with fake engine`() = runBlocking {
        val id = repo.createPendingItem("テストの本文", null, "test")

        val worker = TestListenableWorkerBuilder<SummarizeWorker>(ctx)
            .setInputData(Data.Builder().putString(SummarizeWorker.KEY_ITEM_ID, id).build())
            .setWorkerFactory(
                TestSummarizeWorkerFactory(repo, FakeLlmEngine(), ContentHintDetector(), NotificationHelper(ctx))
            )
            .build()

        val result = worker.doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        val item = repo.getById(id)!!
        assertThat(item.status).isEqualTo(ItemStatus.COMPLETED)
        assertThat(item.summary).isNotEmpty()
    }
}
