package com.example.aiinbox.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun modelFilePath(variant: ModelVariant): File {
        val dir = File(context.noBackupFilesDir, "models").apply { mkdirs() }
        return File(dir, modelFileName(variant))
    }

    fun isModelPresent(variant: ModelVariant): Boolean {
        val f = modelFilePath(variant)
        return f.exists() && f.length() > 0
    }

    fun currentVariant(): ModelVariant? {
        return ModelVariant.entries.firstOrNull {
            it != ModelVariant.FAKE && isModelPresent(it)
        }
    }

    fun deleteModel(variant: ModelVariant) {
        modelFilePath(variant).delete()
    }

    fun expectedSizeBytes(variant: ModelVariant): Long = when (variant) {
        // 実測値（2026-05時点、Hugging Face Content-Length より）
        ModelVariant.GEMMA_4_E2B -> 2_003_697_664L  // ≈1.87 GB
        ModelVariant.GEMMA_4_E4B -> 2_964_324_352L  // ≈2.76 GB
        ModelVariant.FAKE -> 0L
    }

    open fun downloadUrl(variant: ModelVariant): String = when (variant) {
        // MediaPipe LLM Inference 用の .task ファイルは litert-community 配下に配布されている。
        // google/gemma-4-* リポは .safetensors のみで MediaPipe には使えない。
        ModelVariant.GEMMA_4_E2B ->
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-web.task"
        ModelVariant.GEMMA_4_E4B ->
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-web.task"
        ModelVariant.FAKE -> error("FAKE variant has no URL")
    }

    private fun modelFileName(variant: ModelVariant): String = when (variant) {
        ModelVariant.GEMMA_4_E2B -> "gemma-4-e2b-q4km.task"
        ModelVariant.GEMMA_4_E4B -> "gemma-4-e4b-q4km.task"
        ModelVariant.FAKE -> "fake.task"
    }
}
