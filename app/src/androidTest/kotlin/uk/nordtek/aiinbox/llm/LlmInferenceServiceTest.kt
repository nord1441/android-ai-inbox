package uk.nordtek.aiinbox.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LlmInferenceServiceTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var client: LlmServiceClient

    @Test
    fun `submitting a job returns success with FakeLlmEngine bound`() = runBlocking {
        hiltRule.inject()
        val r = client.submit(
            "テスト本文",
            ContentHint.MEMO,
            ModelVariant.FAKE,
        )
        assertThat(r.isSuccess).isTrue()
        assertThat(r.getOrNull()?.summary).isNotEmpty()
    }
}
