package com.example.aiinbox.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RamDetectorTest {
    @Test
    fun `selects GEMMA_3_1B for any RAM size (only available variant)`() {
        // RAM-based selection is degenerate while we ship a single variant.
        // Re-introduce a meaningful split when a 4B+ option is added.
        assertThat(RamDetector.selectVariant(totalRamBytes = 4L * 1024 * 1024 * 1024))
            .isEqualTo(ModelVariant.GEMMA_3_1B)
        assertThat(RamDetector.selectVariant(totalRamBytes = 8L * 1024 * 1024 * 1024))
            .isEqualTo(ModelVariant.GEMMA_3_1B)
        assertThat(RamDetector.selectVariant(totalRamBytes = 12L * 1024 * 1024 * 1024))
            .isEqualTo(ModelVariant.GEMMA_3_1B)
    }
}
