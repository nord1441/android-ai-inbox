package uk.nordtek.aiinbox.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AttachmentTest {
    @Test
    fun `attachment defaults to null OCR fields`() {
        val a = Attachment(
            id = "a1",
            itemId = "i1",
            ordering = 0,
            kind = AttachmentKind.SCREENSHOT,
            encryptedFilename = "x.jpg.enc",
            mimeType = "image/jpeg",
            widthPx = 1080,
            heightPx = 1920,
            byteSize = 12345L,
            createdAt = 1L,
        )
        assertThat(a.ocrText).isNull()
        assertThat(a.ocrCompletedAt).isNull()
    }

    @Test
    fun `inboxItemWithAttachments preserves ordering`() {
        val item = InboxItem(
            id = "i1",
            originalText = null,
            originalSubject = null,
            sourceApp = null,
            receivedAt = 0L,
            status = ItemStatus.PENDING,
            updatedAt = 0L,
        )
        val a0 = Attachment("a0", "i1", 0, AttachmentKind.SHARED_IMAGE, "0.jpg.enc", "image/jpeg", 1, 1, 1L, null, null, 0L)
        val a1 = Attachment("a1", "i1", 1, AttachmentKind.SHARED_IMAGE, "1.jpg.enc", "image/jpeg", 1, 1, 1L, null, null, 0L)
        val w = InboxItemWithAttachments(item, listOf(a0, a1))
        assertThat(w.attachments.map { it.ordering }).containsExactly(0, 1).inOrder()
    }
}
