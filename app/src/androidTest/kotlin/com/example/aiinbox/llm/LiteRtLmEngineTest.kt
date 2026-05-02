package com.example.aiinbox.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

@LargeTest
@RunWith(AndroidJUnit4::class)
class LiteRtLmEngineTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var engine: LiteRtLmEngine
    private val variant = ModelVariant.GEMMA_4_E2B

    @Before
    fun setup() {
        val modelManager = ModelManager(ctx)
        assumeTrue(
            "Skipping: model file not present at ${modelManager.modelFilePath(variant)}",
            modelManager.isModelPresent(variant)
        )
        engine = LiteRtLmEngine(
            ctx,
            modelManager,
            PromptBuilder(),
            LlmResponseParser(ZoneId.systemDefault()),
        )
    }

    @Test
    fun `summarize returns non-empty title and summary for memo`() = runBlocking {
        engine.ensureLoaded(variant)
        val r = engine.summarize(
            "今日のランチで佐藤さんと渋谷の新しいラーメン屋に行った。次回は来週木曜18時に同じ店で集合予定。",
            ContentHint.MEMO,
        )
        assertThat(r.summary).isNotEmpty()
        assertThat(r.event).isNotNull()
        engine.unload()
    }
}
