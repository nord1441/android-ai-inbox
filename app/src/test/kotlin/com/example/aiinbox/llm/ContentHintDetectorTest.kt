package com.example.aiinbox.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContentHintDetectorTest {
    private val det = ContentHintDetector()

    @Test
    fun `url-prefixed text is web article`() {
        val text = "https://example.com/article\n\nThis is the article body..."
        assertThat(det.detect(text)).isEqualTo(ContentHint.WEB_ARTICLE)
    }

    @Test
    fun `chat-style with sender prefix is chat`() {
        val text = "田中: 明日の打ち合わせ何時から?\n佐藤: 14時だよ"
        assertThat(det.detect(text)).isEqualTo(ContentHint.CHAT_OR_EMAIL)
    }

    @Test
    fun `email-style with header is chat or email`() {
        val text = "From: foo@example.com\nTo: bar@example.com\nSubject: meeting\n\nlet's meet at 3pm tomorrow"
        assertThat(det.detect(text)).isEqualTo(ContentHint.CHAT_OR_EMAIL)
    }

    @Test
    fun `plain text is memo`() {
        val text = "今日のランチは美味しかった。明日はカレーにしよう。"
        assertThat(det.detect(text)).isEqualTo(ContentHint.MEMO)
    }

    @Test
    fun `empty text is unknown`() {
        assertThat(det.detect("")).isEqualTo(ContentHint.UNKNOWN)
        assertThat(det.detect("   ")).isEqualTo(ContentHint.UNKNOWN)
    }
}
