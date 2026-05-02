package com.example.aiinbox.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.example.aiinbox.data.crypto.KeystorePassphraseProvider
import com.example.aiinbox.data.db.AppDatabase
import com.example.aiinbox.data.db.ExtractedEvent
import com.example.aiinbox.data.db.buildEncryptedDatabase
import com.example.aiinbox.ui.inbox.InboxFilter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxRepositoryFilterTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: InboxRepository

    @Before
    fun setup() {
        ctx.deleteDatabase("inbox.db")
        db = buildEncryptedDatabase(ctx, KeystorePassphraseProvider(ctx))
        repo = InboxRepository(db.inboxDao())
    }

    @After
    fun teardown() {
        db.close()
        ctx.deleteDatabase("inbox.db")
    }

    @Test
    fun `category filter narrows results`() = runBlocking {
        val id1 = repo.createPendingItem("a", null, null)
        val id2 = repo.createPendingItem("b", null, null)
        db.inboxDao().getById(id1)?.copy(category = "仕事")?.let { db.inboxDao().update(it) }
        db.inboxDao().getById(id2)?.copy(category = "個人")?.let { db.inboxDao().update(it) }

        repo.observeFiltered(InboxFilter(categories = setOf("仕事"))).test {
            assertThat(awaitItem().map { it.id }).containsExactly(id1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tag filter matches any tag`() = runBlocking {
        val id1 = repo.createPendingItem("a", null, null)
        val id2 = repo.createPendingItem("b", null, null)
        db.inboxDao().getById(id1)?.copy(tags = listOf("urgent", "work"))?.let { db.inboxDao().update(it) }
        db.inboxDao().getById(id2)?.copy(tags = listOf("personal"))?.let { db.inboxDao().update(it) }

        repo.observeFiltered(InboxFilter(tags = setOf("urgent", "personal"))).test {
            assertThat(awaitItem().map { it.id }).containsExactly(id1, id2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `short CJK query routes to LIKE fallback`() = runBlocking {
        val id1 = repo.createPendingItem("a", null, null)
        val id2 = repo.createPendingItem("b", null, null)
        db.inboxDao().getById(id1)?.copy(summary = "東京の話")?.let { db.inboxDao().update(it) }
        db.inboxDao().getById(id2)?.copy(summary = "大阪の話")?.let { db.inboxDao().update(it) }

        // 2-char query -> LIKE path (FTS trigram cannot match <3 chars)
        repo.observeFiltered(InboxFilter(query = "東京")).test {
            assertThat(awaitItem().map { it.id }).containsExactly(id1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `long CJK query routes to FTS path`() = runBlocking {
        val id1 = repo.createPendingItem("a", null, null)
        val id2 = repo.createPendingItem("b", null, null)
        db.inboxDao().getById(id1)?.copy(summary = "明日の打ち合わせの件")?.let { db.inboxDao().update(it) }
        db.inboxDao().getById(id2)?.copy(summary = "週末の予定")?.let { db.inboxDao().update(it) }

        // 5-char query -> FTS trigram path
        repo.observeFiltered(InboxFilter(query = "打ち合わせ")).test {
            assertThat(awaitItem().map { it.id }).containsExactly(id1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `blank query returns all matching hasEventOnly`() = runBlocking {
        val id1 = repo.createPendingItem("a", null, null)
        val id2 = repo.createPendingItem("b", null, null)
        val event = ExtractedEvent(
            title = "Meeting",
            startMillis = 1_700_000_000_000L,
            endMillis = null,
            location = null,
            confidence = 0.9f,
        )
        db.inboxDao().getById(id1)?.copy(event = event)?.let { db.inboxDao().update(it) }
        // id2 left without event

        repo.observeFiltered(InboxFilter(hasEventOnly = true)).test {
            assertThat(awaitItem().map { it.id }).containsExactly(id1)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
