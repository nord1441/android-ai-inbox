package com.example.aiinbox.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.example.aiinbox.data.crypto.KeystorePassphraseProvider
import com.example.aiinbox.data.db.AppDatabase
import com.example.aiinbox.data.db.ItemStatus
import com.example.aiinbox.data.db.buildEncryptedDatabase
import com.example.aiinbox.llm.SummarizeResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxRepositoryTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: InboxRepository
    private lateinit var attachmentDir: java.io.File

    @Before
    fun setup() {
        ctx.deleteDatabase("inbox.db")
        db = buildEncryptedDatabase(ctx, KeystorePassphraseProvider(ctx))
        attachmentDir = java.io.File(ctx.cacheDir, "attach-${this::class.java.simpleName}").apply { deleteRecursively(); mkdirs() }
        val store = com.example.aiinbox.data.storage.EncryptedImageStore(ctx, attachmentDir)
        repo = InboxRepository(db.inboxDao(), db.attachmentDao(), store)
    }

    @After
    fun teardown() { db.close(); ctx.deleteDatabase("inbox.db"); attachmentDir.deleteRecursively() }

    @Test
    fun `createPendingItem returns id and stores PENDING`() = runBlocking {
        val id = repo.createPendingItem("hello", subject = null, sourceApp = "x")
        val read = repo.getById(id)
        assertThat(read?.status).isEqualTo(ItemStatus.PENDING)
        assertThat(read?.originalText).isEqualTo("hello")
    }

    @Test
    fun `applySummarizeResult preserves user-edited fields`() = runBlocking {
        val id = repo.createPendingItem("text", null, null)
        repo.updateField(id, "summary", "ユーザー手動要約")
        val newResult = SummarizeResult(
            title = "AI title", summary = "AI要約", category = "個人",
            tags = listOf("a"), people = emptyList(), places = emptyList(),
            urls = emptyList(), event = null,
        )
        repo.applySummarizeResult(id, newResult)
        val item = repo.getById(id)!!
        assertThat(item.summary).isEqualTo("ユーザー手動要約") // 保護される
        assertThat(item.title).isEqualTo("AI title")          // 上書きされる
    }

    @Test
    fun `observeAll emits updates`() = runBlocking {
        repo.observeAll().test {
            assertThat(awaitItem()).isEmpty()
            repo.createPendingItem("a", null, null)
            assertThat(awaitItem()).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markFailed sets status and increments attempts`() = runBlocking {
        val id = repo.createPendingItem("x", null, null)
        repo.markFailed(id, "OOM")
        val item = repo.getById(id)!!
        assertThat(item.status).isEqualTo(ItemStatus.FAILED)
        assertThat(item.processingAttempts).isEqualTo(1)
        assertThat(item.lastError).isEqualTo("OOM")
    }
}
