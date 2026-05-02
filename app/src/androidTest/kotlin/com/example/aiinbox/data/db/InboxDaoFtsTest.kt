package com.example.aiinbox.data.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aiinbox.data.crypto.KeystorePassphraseProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxDaoFtsTest {

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

    @Test
    fun `fts search finds item by summary token`() {
        runBlocking {
            dao.insert(
                InboxItem(
                    id = "1", originalText = "本文1",
                    originalSubject = null, sourceApp = null, receivedAt = 1L,
                    status = ItemStatus.COMPLETED,
                    title = "出張", summary = "東京から大阪へ",
                    tags = listOf("仕事"), people = listOf("田中"), places = listOf("大阪"),
                    updatedAt = 1L,
                )
            )
            dao.insert(
                InboxItem(
                    id = "2", originalText = "本文2",
                    originalSubject = null, sourceApp = null, receivedAt = 2L,
                    status = ItemStatus.COMPLETED,
                    title = "ランチ", summary = "新しいラーメン屋",
                    tags = listOf("食事"), people = emptyList(), places = listOf("渋谷"),
                    updatedAt = 2L,
                )
            )

            // trigram tokenizer requires queries >= 3 chars; 大阪へ is a substring of 東京から大阪へ
            val r = dao.searchFts("大阪へ")
            assertThat(r.map { it.id }).containsExactly("1")
        }
    }

    @Test
    fun `fts search hits across multiple columns`() {
        runBlocking {
            dao.insert(
                InboxItem(
                    id = "x", originalText = "本文",
                    originalSubject = null, sourceApp = null, receivedAt = 1L,
                    status = ItemStatus.COMPLETED,
                    title = null, summary = null,
                    tags = listOf("会議室"), people = listOf("山田太郎"), places = emptyList(),
                    updatedAt = 1L,
                )
            )
            // trigram tokenizer requires queries >= 3 chars
            assertThat(dao.searchFts("会議室").map { it.id }).containsExactly("x")
            assertThat(dao.searchFts("山田太").map { it.id }).containsExactly("x")
        }
    }
}
