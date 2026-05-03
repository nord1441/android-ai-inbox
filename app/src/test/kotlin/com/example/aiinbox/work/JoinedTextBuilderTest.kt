package com.example.aiinbox.work

import com.example.aiinbox.data.db.Attachment
import com.example.aiinbox.data.db.AttachmentKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JoinedTextBuilderTest {

    private fun att(idx: Int, kind: AttachmentKind, ocr: String?) = Attachment(
        id = "a$idx", itemId = "i", ordering = idx, kind = kind,
        encryptedFilename = "$idx.jpg.enc", mimeType = "image/jpeg",
        widthPx = 1, heightPx = 1, byteSize = 1L, ocrText = ocr,
        ocrCompletedAt = if (ocr != null) 1L else null, createdAt = 1L,
    )

    @Test
    fun textOnly_returnsTextAsIs() {
        val out = JoinedTextBuilder.build("hello", emptyList())
        assertThat(out).isEqualTo("hello")
    }

    @Test
    fun emptyTextAndNoAttachments_returnsEmpty() {
        val out = JoinedTextBuilder.build(null, emptyList())
        assertThat(out).isEmpty()
    }

    @Test
    fun screenshotOnly_formatsWithLabel() {
        val out = JoinedTextBuilder.build(
            text = null,
            attachments = listOf(att(0, AttachmentKind.SCREENSHOT, "alice: hi\nbob: hello")),
        )
        assertThat(out).isEqualTo("[添付1: スクリーンショット]\nalice: hi\nbob: hello")
    }

    @Test
    fun mixedAttachmentsAndText_concatenatesWithBlankLines() {
        val out = JoinedTextBuilder.build(
            text = "本文だよ",
            attachments = listOf(
                att(0, AttachmentKind.SCREENSHOT, "ocr1"),
                att(1, AttachmentKind.SHARED_IMAGE, "ocr2"),
            ),
        )
        assertThat(out).isEqualTo(
            "[添付1: スクリーンショット]\nocr1\n\n[添付2: 画像]\nocr2\n\n[本文]\n本文だよ"
        )
    }

    @Test
    fun attachmentWithoutOcr_showsPlaceholder() {
        val out = JoinedTextBuilder.build(
            text = null,
            attachments = listOf(att(0, AttachmentKind.SHARED_IMAGE, null)),
        )
        assertThat(out).isEqualTo("[添付1: 画像]\n（テキスト抽出未完了）")
    }

    @Test
    fun emptyOcrText_showsTextlessPlaceholder() {
        val out = JoinedTextBuilder.build(
            text = null,
            attachments = listOf(att(0, AttachmentKind.SHARED_IMAGE, "")),
        )
        assertThat(out).isEqualTo("[添付1: 画像]\n（テキストなし）")
    }
}
