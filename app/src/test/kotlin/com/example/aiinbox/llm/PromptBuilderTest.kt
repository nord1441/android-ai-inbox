package com.example.aiinbox.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PromptBuilderTest {
    private val pb = PromptBuilder()

    @Test
    fun `prompt contains schema instructions and input text`() {
        val prompt = pb.build("Hello world", ContentHint.MEMO)
        assertThat(prompt).contains("\"title\"")
        assertThat(prompt).contains("\"summary\"")
        assertThat(prompt).contains("\"event\"")
        assertThat(prompt).contains("Hello world")
    }

    @Test
    fun `chat hint adds date interpretation guidance`() {
        val prompt = pb.build("田中: 明日10時集合", ContentHint.CHAT_OR_EMAIL)
        assertThat(prompt).contains("日付")
    }

    @Test
    fun `truncates very long input`() {
        val long = "あ".repeat(20_000)
        val prompt = pb.build(long, ContentHint.WEB_ARTICLE)
        assertThat(prompt.length).isLessThan(15_000)
    }
}
