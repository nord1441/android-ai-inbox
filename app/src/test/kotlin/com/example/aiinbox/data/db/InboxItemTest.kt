package com.example.aiinbox.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InboxItemTest {
    @Test
    fun `default item has empty collections and pending status`() {
        val item = InboxItem(
            id = "abc",
            originalText = "hello",
            originalSubject = null,
            sourceApp = "com.example.foo",
            receivedAt = 1000L,
            status = ItemStatus.PENDING,
            updatedAt = 1000L,
        )
        assertThat(item.tags).isEmpty()
        assertThat(item.people).isEmpty()
        assertThat(item.places).isEmpty()
        assertThat(item.urls).isEmpty()
        assertThat(item.userEditedFields).isEmpty()
        assertThat(item.event).isNull()
        assertThat(item.processingAttempts).isEqualTo(0)
        assertThat(item.lastError).isNull()
    }

    @Test
    fun `extracted event has confidence in 0 to 1`() {
        val event = ExtractedEvent(
            title = "ミーティング",
            startMillis = 1700000000000L,
            endMillis = null,
            location = "新宿",
            confidence = 0.8f,
        )
        assertThat(event.confidence).isIn(com.google.common.collect.Range.closed(0f, 1f))
    }

    @Test
    fun `inboxItem allows null originalText`() {
        val item = InboxItem(
            id = "i1",
            originalText = null,
            originalSubject = null,
            sourceApp = "screenshot:capture",
            receivedAt = 1L,
            status = ItemStatus.PENDING,
            updatedAt = 1L,
        )
        assertThat(item.originalText).isNull()
    }
}
