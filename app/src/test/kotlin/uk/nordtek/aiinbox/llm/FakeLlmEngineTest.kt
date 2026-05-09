package uk.nordtek.aiinbox.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeLlmEngineTest {
    @Test
    fun `default fake produces non-empty summary and tags`() = runTest {
        val engine = FakeLlmEngine()
        engine.ensureLoaded(ModelVariant.FAKE)
        val r = engine.summarize("これはテストの本文です。", ContentHint.MEMO)
        assertThat(r.summary).isNotEmpty()
        assertThat(r.title).isNotEmpty()
        assertThat(r.category).isNotEmpty()
    }

    @Test
    fun `fake detects fake event when text contains date marker`() = runTest {
        val engine = FakeLlmEngine()
        val r = engine.summarize("__FAKE_EVENT__明日の打ち合わせ", ContentHint.CHAT_OR_EMAIL)
        assertThat(r.event).isNotNull()
        assertThat(r.event!!.title).contains("打ち合わせ")
    }

    @Test
    fun `loaded state toggles correctly`() = runTest {
        val engine = FakeLlmEngine()
        assertThat(engine.isLoaded.value).isFalse()
        engine.ensureLoaded(ModelVariant.FAKE)
        assertThat(engine.isLoaded.value).isTrue()
        engine.unload()
        assertThat(engine.isLoaded.value).isFalse()
    }
}
