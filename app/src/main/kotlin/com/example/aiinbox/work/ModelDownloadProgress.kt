package com.example.aiinbox.work

sealed interface ModelDownloadProgress {
    data object Idle : ModelDownloadProgress
    data class InProgress(val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadProgress
    data class Completed(val variant: com.example.aiinbox.llm.ModelVariant) : ModelDownloadProgress
    data class Failed(val message: String) : ModelDownloadProgress
}
