package uk.nordtek.aiinbox.ui.modeldownload

import uk.nordtek.aiinbox.llm.ModelVariant

data class ModelDownloadUiState(
    val variant: ModelVariant = ModelVariant.GEMMA_4_E2B,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val isDownloading: Boolean = false,
    val isCompleted: Boolean = false,
    val errorMessage: String? = null,
    val showMobileNetworkWarning: Boolean = false,
    val canStart: Boolean = true,
    /** True once the user has stored a HuggingFace access token. */
    val hasHfToken: Boolean = false,
)
