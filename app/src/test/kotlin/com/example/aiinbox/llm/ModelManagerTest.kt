package com.example.aiinbox.llm

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelManagerTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val manager = ModelManager(ctx)

    @Test
    fun `model not present initially`() {
        assertThat(manager.isModelPresent(ModelVariant.GEMMA_4_E2B)).isFalse()
    }

    @Test
    fun `model file path is under no-backup files dir`() {
        val path = manager.modelFilePath(ModelVariant.GEMMA_4_E2B)
        assertThat(path.absolutePath).contains(ctx.noBackupFilesDir.absolutePath)
        assertThat(path.name).endsWith(".task")
    }

    @Test
    fun `delete removes the file`() {
        val path = manager.modelFilePath(ModelVariant.GEMMA_4_E2B)
        path.parentFile?.mkdirs()
        path.writeBytes(ByteArray(16))
        assertThat(manager.isModelPresent(ModelVariant.GEMMA_4_E2B)).isTrue()
        manager.deleteModel(ModelVariant.GEMMA_4_E2B)
        assertThat(manager.isModelPresent(ModelVariant.GEMMA_4_E2B)).isFalse()
    }

    @Test
    fun `currentVariant returns variant whose file exists`() {
        val path = manager.modelFilePath(ModelVariant.GEMMA_4_E2B)
        path.parentFile?.mkdirs()
        path.writeBytes(ByteArray(16))
        assertThat(manager.currentVariant()).isEqualTo(ModelVariant.GEMMA_4_E2B)
    }
}
