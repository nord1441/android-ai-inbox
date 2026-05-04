package com.example.aiinbox.ui.settings

import com.example.aiinbox.llm.ModelVariant

data class SettingsUiState(
    val currentVariant: ModelVariant? = null,
    val modelSizeBytes: Long = 0,
    val dbSizeBytes: Long = 0,
    val versionName: String = "",
    val driveAccountEmail: String? = null,
    val isLinkingInProgress: Boolean = false,
    val driveLinkError: String? = null,
)
