package com.example.aiinbox.sync

import com.example.aiinbox.data.db.Attachment
import com.example.aiinbox.data.db.AttachmentKind
import com.example.aiinbox.data.db.ExtractedEvent
import com.example.aiinbox.data.db.InboxItem
import com.example.aiinbox.data.db.ItemStatus
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MarkdownExporterTest {

    private val exporter = MarkdownExporter(zone = ZoneId.of("Asia/Tokyo"))

    private fun item(
        id: String = "abc",
        receivedAt: Long = 1777865734000L,  // 2026-05-04T12:35:34+09:00
        deletedAt: Long? = null,
        title: String? = "title",
        summary: String? = "the summary body",
    ) = InboxItem(
        id = id,
        originalText = null,
        originalSubject = null,
        sourceApp = "test",
        receivedAt = receivedAt,
        status = ItemStatus.COMPLETED,
        title = title,
        summary = summary,
        category = "買い物",
        tags = listOf("a", "b"),
        people = listOf("alice"),
        places = listOf("shop"),
        urls = listOf("https://example.com"),
        event = ExtractedEvent(
            title = "meet",
            startMillis = 1746400000000L,
            endMillis = null,
            location = "office",
            confidence = 0.9f,
        ),
        userEditedFields = emptySet(),
        updatedAt = receivedAt,
        deletedAt = deletedAt,
    )

    private fun attachment(id: String = "att-1", mimeType: String = "image/jpeg") = Attachment(
        id = id,
        itemId = "abc",
        ordering = 0,
        kind = AttachmentKind.SHARED_IMAGE,
        encryptedFilename = "enc-1.jpg.enc",
        mimeType = mimeType,
        widthPx = 800,
        heightPx = 600,
        byteSize = 12345L,
        ocrText = "scanned text",
        ocrCompletedAt = 1746345400000L,
        createdAt = 1777865734000L,
    )

    @Test
    fun encode_aliveItem_includesFrontmatterAndBody() {
        val bytes = exporter.encode(item(), listOf(attachment()))
        val text = bytes.toString(Charsets.UTF_8)

        assertTrue("must start with frontmatter delimiter", text.startsWith("---\n"))
        assertTrue("must contain second delimiter", text.contains("\n---\n"))
        assertTrue("must contain id", text.contains("id: \"abc\"") || text.contains("id: abc"))
        assertTrue("must contain title", text.contains("title:"))
        assertTrue("must contain attachments", text.contains("attachments:"))
        assertTrue("must contain attachment file path", text.contains("attachments/att-1.jpg"))
        assertTrue("body must end with summary", text.endsWith("the summary body\n"))
    }

    @Test
    fun encode_tombstone_dropsBodyAndMostFields() {
        val bytes = exporter.encode(
            item(deletedAt = 1746345400000L, title = "to be hidden", summary = "secret"),
            emptyList(),
        )
        val text = bytes.toString(Charsets.UTF_8)

        assertTrue(
            "must include status DELETED",
            text.contains("status: DELETED") || text.contains("status: \"DELETED\""),
        )
        assertTrue("must not leak title", !text.contains("to be hidden"))
        assertTrue("must not leak summary", !text.contains("secret"))
        assertTrue("must include deleted_at", text.contains("deleted_at:"))
    }

    @Test
    fun filenameFor_usesReceivedAtDateInZone() {
        val name = exporter.filenameFor(item(id = "xyz", receivedAt = 1777865734000L))
        assertTrue("filename must start with date prefix in Asia/Tokyo", name.startsWith("2026-05-04-xyz"))
        assertTrue("must end with .md", name.endsWith(".md"))
    }

    @Test
    fun attachmentFilename_mapsMimeToExt() {
        val jpg = exporter.attachmentFilename(attachment(id = "a", mimeType = "image/jpeg"))
        val png = exporter.attachmentFilename(attachment(id = "b", mimeType = "image/png"))
        val unk = exporter.attachmentFilename(attachment(id = "c", mimeType = "application/octet-stream"))
        assertTrue(jpg.endsWith(".jpg"))
        assertTrue(png.endsWith(".png"))
        assertTrue(unk.endsWith(".bin"))
    }
}
