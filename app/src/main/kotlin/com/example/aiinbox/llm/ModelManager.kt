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
        // Gemma 3 1B IT q4 block128 ekv4096 (placeholder — actual size unverified
        // because the file is HF-gated; user verifies after download).
        ModelVariant.GEMMA_3_1B -> 689_000_000L  // ≈689 MB
        ModelVariant.FAKE -> 0L
    }

    open fun downloadUrl(variant: ModelVariant): String = when (variant) {
        // litert-community ships Gemma 3 1B in the older "multi-prefill-seq"
        // bundle naming (no -web suffix), which IS a MediaPipe Tasks GenAI
        // compatible zip. The repo is HF-gated under the Gemma license; users
        // must accept the license once on huggingface.co before this URL
        // becomes downloadable. App-side download will currently 401 — the
        // workaround until HF token auth is implemented is to download the
        // file manually via browser and `adb push` it to the device's
        // no_backup/files/models/ directory under the local file name.
        ModelVariant.GEMMA_3_1B ->
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/" +
                "Gemma3-1B-IT_multi-prefill-seq_q4_block128_ekv4096.task"
        ModelVariant.FAKE -> error("FAKE variant has no URL")
    }

    private fun modelFileName(variant: ModelVariant): String = when (variant) {
        ModelVariant.GEMMA_3_1B -> "gemma-3-1b-q4-ekv4096.task"
        ModelVariant.FAKE -> "fake.task"
    }
}
