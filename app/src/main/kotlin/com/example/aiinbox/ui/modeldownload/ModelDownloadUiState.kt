package com.example.aiinbox.ui.modeldownload

import com.example.aiinbox.llm.ModelVariant

data class ModelDownloadUiState(
    val variant: ModelVariant = ModelVariant.GEMMA_3_1B,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val isDownloading: Boolean = false,
    val isCompleted: Boolean = false,
    val errorMessage: String? = null,
    val showMobileNetworkWarning: Boolean = false,
    val canStart: Boolean = true,
)
