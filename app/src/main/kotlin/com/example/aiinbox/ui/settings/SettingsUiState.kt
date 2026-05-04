package com.example.aiinbox.ui.settings

import com.example.aiinbox.llm.ModelVariant
import com.example.aiinbox.sync.SyncState

data class SettingsUiState(
    val currentVariant: ModelVariant? = null,
    val modelSizeBytes: Long = 0,
    val dbSizeBytes: Long = 0,
    val versionName: String = "",
    val driveAccountEmail: String? = null,
    val isLinkingInProgress: Boolean = false,
    val driveLinkError: String? = null,
    val syncRuntime: SyncState = SyncState.Idle,
    val lastFullSyncAt: Long? = null,
    /** Periodic sync interval in minutes; null means manual-only. */
    val syncIntervalMinutes: Long? = 30L,
)
