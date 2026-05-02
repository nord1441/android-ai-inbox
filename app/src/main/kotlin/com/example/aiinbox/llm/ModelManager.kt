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
        ModelVariant.GEMMA_4_E2B -> 1_300_000_000L
        ModelVariant.GEMMA_4_E4B -> 2_500_000_000L
        ModelVariant.FAKE -> 0L
    }

    open fun downloadUrl(variant: ModelVariant): String = when (variant) {
        // TODO: 実DL URLは2026年5月時点で確認・変更すること（Hugging Face / Google CDN）
        ModelVariant.GEMMA_4_E2B ->
            "https://huggingface.co/google/gemma-4-e2b-it/resolve/main/gemma-4-e2b-it-q4_k_m.task"
        ModelVariant.GEMMA_4_E4B ->
            "https://huggingface.co/google/gemma-4-e4b-it/resolve/main/gemma-4-e4b-it-q4_k_m.task"
        ModelVariant.FAKE -> error("FAKE variant has no URL")
    }

    private fun modelFileName(variant: ModelVariant): String = when (variant) {
        ModelVariant.GEMMA_4_E2B -> "gemma-4-e2b-q4km.task"
        ModelVariant.GEMMA_4_E4B -> "gemma-4-e4b-q4km.task"
        ModelVariant.FAKE -> "fake.task"
    }
}
