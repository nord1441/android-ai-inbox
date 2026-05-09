package uk.nordtek.aiinbox.data.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import uk.nordtek.aiinbox.data.crypto.KeystorePassphraseProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxDaoFilterTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var dao: InboxDao

    @Before
    fun setup() {
        ctx.deleteDatabase("inbox.db")
        db = buildEncryptedDatabase(ctx, KeystorePassphraseProvider(ctx))
        dao = db.inboxDao()
    }

    @After
    fun teardown() {
        db.close()
        ctx.deleteDatabase("inbox.db")
    }

    private fun item(id: String, hasEvent: Boolean = false) = InboxItem(
        id = id,
        originalText = "本文$id",
        originalSubject = null,
        sourceApp = null,
        receivedAt = id.hashCode().toLong(),
        status = ItemStatus.COMPLETED,
        summary = "summary $id",
        title = "t$id",
        event = if (hasEvent) ExtractedEvent("ev$id", null, null, null, 0.5f) else null,
        updatedAt = 0L,
    )

    @Test
    fun `observeFiltered hasEventOnly returns only items with event`() = runBlocking {
        dao.insert(item("a", hasEvent = false))
        dao.insert(item("b", hasEvent = true))
        dao.observeFiltered(hasEventOnly = 1).test {
            assertThat(awaitItem().map { it.id }).containsExactly("b")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeSearch with hasEvent filter`() = runBlocking {
        dao.insert(item("a", hasEvent = false).copy(summary = "東京の話"))
        dao.insert(item("b", hasEvent = true).copy(summary = "東京の打ち合わせ"))
        // trigram tokenizer requires queries >= 3 chars; 東京の matches both rows
        // so only the hasEventOnly filter narrows the result to "b".
        dao.observeSearch("東京の", hasEventOnly = 1).test {
            assertThat(awaitItem().map { it.id }).containsExactly("b")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeSearchLike matches short CJK substring with hasEvent filter`() {
        runBlocking {
            dao.insert(item("a", hasEvent = false).copy(summary = "東京の話"))
            dao.insert(item("b", hasEvent = true).copy(summary = "東京の打ち合わせ"))
            dao.insert(item("c", hasEvent = true).copy(summary = "大阪へ出張"))
            dao.observeSearchLike("%東京%", hasEventOnly = 1).test {
                assertThat(awaitItem().map { it.id }).containsExactly("b")
                cancelAndIgnoreRemainingEvents()
            }
        }
    }
}
