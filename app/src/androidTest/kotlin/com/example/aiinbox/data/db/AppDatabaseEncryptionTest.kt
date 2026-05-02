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
class AppDatabaseEncryptionTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        ctx.deleteDatabase("inbox.db")
        val provider = KeystorePassphraseProvider(ctx)
        db = buildEncryptedDatabase(ctx, provider)
    }

    @After
    fun teardown() {
        db.close()
        ctx.deleteDatabase("inbox.db")
    }

    @Test
    fun `insert and read item works with encrypted db`() = runBlocking {
        val item = InboxItem(
            id = "id-1",
            originalText = "hello",
            originalSubject = null,
            sourceApp = "test",
            receivedAt = 1000L,
            status = ItemStatus.PENDING,
            updatedAt = 1000L,
        )
        db.inboxDao().insert(item)
        val read = db.inboxDao().getById("id-1")
        assertThat(read?.originalText).isEqualTo("hello")
    }

    @Test
    fun `db file does not contain plaintext`() = runBlocking {
        val item = InboxItem(
            id = "id-2",
            originalText = "PLAINTEXT_MARKER_zzz",
            originalSubject = null,
            sourceApp = "test",
            receivedAt = 1000L,
            status = ItemStatus.PENDING,
            updatedAt = 1000L,
        )
        db.inboxDao().insert(item)
        db.close()

        val dbFile = ctx.getDatabasePath("inbox.db")
        val bytes = dbFile.readBytes()
        val asString = String(bytes, Charsets.ISO_8859_1)
        assertThat(asString).doesNotContain("PLAINTEXT_MARKER_zzz")
    }
}
