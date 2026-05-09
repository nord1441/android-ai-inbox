package uk.nordtek.aiinbox.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import uk.nordtek.aiinbox.data.crypto.KeystorePassphraseProvider
import uk.nordtek.aiinbox.data.db.AppDatabase
import uk.nordtek.aiinbox.data.db.AttachmentKind
import uk.nordtek.aiinbox.data.db.buildEncryptedDatabase
import uk.nordtek.aiinbox.data.storage.EncryptedImageStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InboxRepositoryAttachmentTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: InboxRepository
    private lateinit var store: EncryptedImageStore
    private lateinit var dir: File

    @Before
    fun setup() {
        ctx.deleteDatabase("inbox.db")
        db = buildEncryptedDatabase(ctx, KeystorePassphraseProvider(ctx))
        dir = File(ctx.cacheDir, "attach-test").apply { deleteRecursively(); mkdirs() }
        store = EncryptedImageStore(ctx, dir)
        repo = InboxRepository(db.inboxDao(), db.attachmentDao(), store)
    }

    @After
    fun tearDown() {
        db.close()
        ctx.deleteDatabase("inbox.db")
        dir.deleteRecursively()
    }

    @Test
    fun createPendingItemWithAttachments_persistsItemAndAttachments() = runBlocking {
        val name = store.save(byteArrayOf(1, 2, 3))
        val drafts = listOf(
            AttachmentDraft(
                kind = AttachmentKind.SCREENSHOT,
                encryptedFilename = name,
                mimeType = "image/jpeg",
                widthPx = 100, heightPx = 200, byteSize = 3L,
            )
        )
        val itemId = repo.createPendingItemWithAttachments(
            text = null, subject = null, sourceApp = "screenshot:capture", drafts = drafts,
        )
        val full = repo.getItemWithAttachments(itemId)!!
        assertThat(full.item.originalText).isNull()
        assertThat(full.attachments).hasSize(1)
        assertThat(full.attachments[0].kind).isEqualTo(AttachmentKind.SCREENSHOT)
        assertThat(full.attachments[0].ordering).isEqualTo(0)
    }

    @Test
    fun softDeleteThenRestore_keepsAttachmentsAndFile() = runBlocking {
        val name = store.save(byteArrayOf(9, 9, 9))
        val drafts = listOf(
            AttachmentDraft(AttachmentKind.SHARED_IMAGE, name, "image/jpeg", 1, 1, 3L)
        )
        val itemId = repo.createPendingItemWithAttachments(null, null, "test", drafts)
        repo.softDelete(itemId)
        assertThat(repo.getItemWithAttachments(itemId)).isNull()
        assertThat(File(dir, name).exists()).isTrue() // Undo中はファイル残す
        repo.restoreDeleted(itemId)
        val restored = repo.getItemWithAttachments(itemId)!!
        assertThat(restored.attachments).hasSize(1)
    }

    @Test
    fun finalizeDelete_removesAttachmentFiles() = runBlocking {
        val name = store.save(byteArrayOf(1, 2, 3))
        val drafts = listOf(
            AttachmentDraft(AttachmentKind.SHARED_IMAGE, name, "image/jpeg", 1, 1, 3L)
        )
        val itemId = repo.createPendingItemWithAttachments(null, null, "t", drafts)
        repo.softDelete(itemId)
        repo.finalizeDelete(itemId)
        assertThat(File(dir, name).exists()).isFalse()
    }

    @Test
    fun updateAttachmentOcr_setsTextAndTimestamp() = runBlocking {
        val name = store.save(byteArrayOf(1, 2, 3))
        val drafts = listOf(
            AttachmentDraft(AttachmentKind.SCREENSHOT, name, "image/jpeg", 1, 1, 3L)
        )
        val itemId = repo.createPendingItemWithAttachments(null, null, "t", drafts)
        val attId = repo.getItemWithAttachments(itemId)!!.attachments[0].id

        repo.updateAttachmentOcr(attId, "hello world")

        val updated = repo.getItemWithAttachments(itemId)!!.attachments[0]
        assertThat(updated.ocrText).isEqualTo("hello world")
        assertThat(updated.ocrCompletedAt).isNotNull()
    }
}
