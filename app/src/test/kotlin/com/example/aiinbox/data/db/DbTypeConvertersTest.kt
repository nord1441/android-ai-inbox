package com.example.aiinbox.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DbTypeConvertersTest {
    private val c = DbTypeConverters()

    @Test
    fun `string list round trip`() {
        val list = listOf("a", "b", "あいうえお")
        assertThat(c.stringListFromJson(c.stringListToJson(list))).isEqualTo(list)
    }

    @Test
    fun `empty string list`() {
        assertThat(c.stringListFromJson(c.stringListToJson(emptyList()))).isEmpty()
    }

    @Test
    fun `null becomes empty for list`() {
        assertThat(c.stringListFromJson(null)).isEmpty()
    }

    @Test
    fun `string set round trip`() {
        val set = setOf("summary", "tags")
        assertThat(c.stringSetFromJson(c.stringSetToJson(set))).isEqualTo(set)
    }

    @Test
    fun `item status round trip`() {
        for (s in ItemStatus.entries) {
            assertThat(c.itemStatusFromString(c.itemStatusToString(s))).isEqualTo(s)
        }
    }
}
