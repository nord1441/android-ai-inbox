package uk.nordtek.aiinbox.ui.settings

import uk.nordtek.aiinbox.llm.ModelVariant

data class SettingsUiState(
    val currentVariant: ModelVariant? = null,
    val modelSizeBytes: Long = 0,
    val dbSizeBytes: Long = 0,
    val versionName: String = "",
    val fsSyncFolderUri: String? = null,
    val fsSyncFolderName: String? = null,
    val fsSyncRuntime: uk.nordtek.aiinbox.sync.FsSyncState = uk.nordtek.aiinbox.sync.FsSyncState.Idle,
    val fsSyncLastFullSyncAt: Long? = null,
    val fsSyncIntervalMinutes: Long? = 30L,
)
