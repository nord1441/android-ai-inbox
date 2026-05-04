package com.example.aiinbox.ui.settings

import com.example.aiinbox.llm.ModelVariant

data class SettingsUiState(
    val currentVariant: ModelVariant? = null,
    val modelSizeBytes: Long = 0,
    val dbSizeBytes: Long = 0,
    val versionName: String = "",
    val fsSyncFolderUri: String? = null,
    val fsSyncFolderName: String? = null,
    val fsSyncRuntime: com.example.aiinbox.sync.FsSyncState = com.example.aiinbox.sync.FsSyncState.Idle,
    val fsSyncLastFullSyncAt: Long? = null,
    val fsSyncIntervalMinutes: Long? = 30L,
)
