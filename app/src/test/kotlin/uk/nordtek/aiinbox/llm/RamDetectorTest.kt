package uk.nordtek.aiinbox.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RamDetectorTest {
    @Test
    fun `selects E4B for 8GB or higher`() {
        assertThat(RamDetector.selectVariant(totalRamBytes = 8L * 1024 * 1024 * 1024))
            .isEqualTo(ModelVariant.GEMMA_4_E4B)
        assertThat(RamDetector.selectVariant(totalRamBytes = 12L * 1024 * 1024 * 1024))
            .isEqualTo(ModelVariant.GEMMA_4_E4B)
    }

    @Test
    fun `selects E2B for 6 to 8GB`() {
        assertThat(RamDetector.selectVariant(totalRamBytes = 6L * 1024 * 1024 * 1024))
            .isEqualTo(ModelVariant.GEMMA_4_E2B)
        assertThat(RamDetector.selectVariant(totalRamBytes = 7L * 1024 * 1024 * 1024))
            .isEqualTo(ModelVariant.GEMMA_4_E2B)
    }

    @Test
    fun `falls back to E2B for under 6GB (best-effort)`() {
        assertThat(RamDetector.selectVariant(totalRamBytes = 4L * 1024 * 1024 * 1024))
            .isEqualTo(ModelVariant.GEMMA_4_E2B)
    }
}
