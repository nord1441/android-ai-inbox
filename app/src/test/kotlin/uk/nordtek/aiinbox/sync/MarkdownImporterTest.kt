package uk.nordtek.aiinbox.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownImporterTest {

    private val importer = MarkdownImporter()

    @Test
    fun parse_aliveItem_returnsEnvelopeAndBody() {
        val text = """
            ---
            id: f08528b4
            received_at: "2026-05-04T12:35:34+09:00"
            updated_at: "2026-05-04T12:35:34+09:00"
            status: COMPLETED
            title: "ほっともっと"
            tags: [a, b]
            ---

            ほっともっとの注文受取案内です。
        """.trimIndent()

        val result = importer.parse(text.toByteArray(Charsets.UTF_8))
        assertTrue("expected Success, got $result", result is MarkdownImporter.ParseResult.Success)
        val ok = result as MarkdownImporter.ParseResult.Success
        assertEquals("f08528b4", ok.envelope.id)
        assertEquals("COMPLETED", ok.envelope.status)
        assertEquals(listOf("a", "b"), ok.envelope.tags)
        assertEquals("ほっともっとの注文受取案内です。", ok.summaryBody)
    }

    @Test
    fun parse_tombstone_returnsDeletedStatusAndEmptyBody() {
        val text = """
            ---
            id: del-1
            received_at: "2026-05-04T00:00:00+09:00"
            updated_at: "2026-05-04T14:00:00+09:00"
            status: DELETED
            deleted_at: "2026-05-04T14:00:00+09:00"
            ---

        """.trimIndent()

        val result = importer.parse(text.toByteArray(Charsets.UTF_8))
        assertTrue(result is MarkdownImporter.ParseResult.Success)
        val ok = result as MarkdownImporter.ParseResult.Success
        assertEquals("DELETED", ok.envelope.status)
        assertEquals("", ok.summaryBody)
    }

    @Test
    fun parse_missingFrontmatter_returnsFailure() {
        val text = "not a frontmatter file\nplain text only"
        val result = importer.parse(text.toByteArray(Charsets.UTF_8))
        assertTrue(result is MarkdownImporter.ParseResult.Failure)
    }

    @Test
    fun parse_malformedYaml_returnsFailure() {
        val text = """
            ---
            id: abc
            tags: [unterminated
            ---

            body
        """.trimIndent()
        val result = importer.parse(text.toByteArray(Charsets.UTF_8))
        assertTrue(result is MarkdownImporter.ParseResult.Failure)
    }

    @Test
    fun parse_blankId_returnsFailure() {
        val text = """
            ---
            id: ""
            received_at: "2026-05-04T00:00:00+09:00"
            updated_at: "2026-05-04T00:00:00+09:00"
            status: COMPLETED
            ---

            body
        """.trimIndent()
        val result = importer.parse(text.toByteArray(Charsets.UTF_8))
        assertTrue(result is MarkdownImporter.ParseResult.Failure)
    }

    @Test
    fun parse_unknownFields_areIgnored() {
        val text = """
            ---
            id: x
            received_at: "2026-05-04T00:00:00+09:00"
            updated_at: "2026-05-04T00:00:00+09:00"
            status: COMPLETED
            future_field: "ignore me"
            ---

            body
        """.trimIndent()
        val result = importer.parse(text.toByteArray(Charsets.UTF_8))
        assertTrue("kaml strictMode=false ignores unknown fields", result is MarkdownImporter.ParseResult.Success)
    }
}
