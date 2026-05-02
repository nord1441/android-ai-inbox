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
        // Use filesDir instead of noBackupFilesDir because some devices
        // restrict write access to no_backup/ subdirs via run-as. allowBackup
        // is already false at the manifest level so backup is excluded
        // anyway.
        val dir = File(context.filesDir, "models").apply { mkdirs() }
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
        // Sizes from HF Content-Length on the .litertlm files.
        ModelVariant.GEMMA_4_E2B -> 2_583_085_056L  // ≈2.40 GB
        ModelVariant.GEMMA_4_E4B -> 3_654_467_584L  // ≈3.40 GB
        ModelVariant.FAKE -> 0L
    }

    open fun downloadUrl(variant: ModelVariant): String = when (variant) {
        // litert-community publishes Gemma 4 in `.litertlm` format for use with
        // the LiteRT-LM Android API. The HF repo is gated under the Gemma
        // license; users must accept the license once at huggingface.co before
        // these URLs become downloadable. App-side download currently 401s
        // until an HF-token-aware ModelDownloadWorker lands. Workaround: pull
        // the file in a browser, then `adb push` it to the device's
        // files/models/<localName>.
        ModelVariant.GEMMA_4_E2B ->
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/" +
                "gemma-4-E2B-it.litertlm"
        ModelVariant.GEMMA_4_E4B ->
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/" +
                "gemma-4-E4B-it.litertlm"
        ModelVariant.FAKE -> error("FAKE variant has no URL")
    }

    private fun modelFileName(variant: ModelVariant): String = when (variant) {
        ModelVariant.GEMMA_4_E2B -> "gemma-4-e2b.litertlm"
        ModelVariant.GEMMA_4_E4B -> "gemma-4-e4b.litertlm"
        ModelVariant.FAKE -> "fake.litertlm"
    }
}
