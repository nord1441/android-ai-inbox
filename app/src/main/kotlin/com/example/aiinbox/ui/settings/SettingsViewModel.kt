package com.example.aiinbox.ui.settings

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.aiinbox.BuildConfig
import com.example.aiinbox.llm.ModelManager
import com.example.aiinbox.llm.RamDetector
import com.example.aiinbox.sync.DriveAuthRepository
import com.example.aiinbox.sync.SyncCoordinator
import com.example.aiinbox.sync.SyncStateRepository
import com.example.aiinbox.work.ModelDownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val PREFS_FILE = "ai_inbox_sync_prefs"
private const val PREF_SYNC_INTERVAL_MINUTES = "sync_interval_minutes"
private const val PREF_SYNC_INTERVAL_MANUAL_ONLY = -1L
private const val PREF_SYNC_INTERVAL_DEFAULT = 30L

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val modelManager: ModelManager,
    private val driveAuthRepository: DriveAuthRepository,
    private val syncStateRepository: SyncStateRepository,
    private val syncCoordinator: SyncCoordinator,
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_FILE, android.content.Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init { refresh() }

    init {
        val storedInterval = prefs.getLong(PREF_SYNC_INTERVAL_MINUTES, PREF_SYNC_INTERVAL_DEFAULT)
        val intervalMinutes = if (storedInterval == PREF_SYNC_INTERVAL_MANUAL_ONLY) null else storedInterval
        _state.update {
            it.copy(
                driveAccountEmail = driveAuthRepository.currentEmail(),
                syncIntervalMinutes = intervalMinutes,
            )
        }
        // Observe sync runtime + persistent state into the UI.
        viewModelScope.launch {
            syncStateRepository.runtime.collect { rt ->
                _state.update { it.copy(syncRuntime = rt) }
            }
        }
        viewModelScope.launch {
            syncStateRepository.lastFullSyncAt.collect { ts ->
                _state.update { it.copy(lastFullSyncAt = ts) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val variant = modelManager.currentVariant()
            val modelSize = variant?.let { modelManager.modelFilePath(it).length() } ?: 0L
            val dbSize = withContext(Dispatchers.IO) {
                getApplication<Application>().getDatabasePath("inbox.db").length()
            }
            // Preserve drive fields so refresh() doesn't clobber link state.
            _state.update {
                it.copy(
                    currentVariant = variant,
                    modelSizeBytes = modelSize,
                    dbSizeBytes = dbSize,
                    versionName = BuildConfig.VERSION_NAME,
                )
            }
        }
    }

    fun onRedownload() {
        viewModelScope.launch {
            val variant = _state.value.currentVariant
                ?: RamDetector.selectVariantForDevice(getApplication())
            modelManager.deleteModel(variant)
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(ModelDownloadWorker.KEY_VARIANT, variant.name)
                        .build()
                )
                .build()
            WorkManager.getInstance(getApplication())
                .enqueueUniqueWork(
                    "model_dl_${variant.name}",
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            refresh()
        }
    }

    fun onLinkDriveClicked(activity: Activity) {
        viewModelScope.launch {
            _state.update { it.copy(isLinkingInProgress = true, driveLinkError = null) }
            val result = driveAuthRepository.link(activity)
            result.onSuccess { tokens ->
                _state.update {
                    it.copy(
                        driveAccountEmail = tokens.accountEmail,
                        isLinkingInProgress = false,
                    )
                }
            }.onFailure { t ->
                _state.update {
                    it.copy(
                        isLinkingInProgress = false,
                        driveLinkError = t.message ?: "リンクに失敗しました",
                    )
                }
            }
        }
    }

    fun onUnlinkDriveClicked() {
        driveAuthRepository.unlink()
        _state.update { it.copy(driveAccountEmail = null, driveLinkError = null) }
    }

    fun onSyncNowClicked() {
        syncCoordinator.requestImmediateSync()
    }

    fun onSyncIntervalSelected(minutes: Long?) {
        _state.update { it.copy(syncIntervalMinutes = minutes) }
        prefs.edit()
            .putLong(PREF_SYNC_INTERVAL_MINUTES, minutes ?: PREF_SYNC_INTERVAL_MANUAL_ONLY)
            .apply()
        syncCoordinator.setPeriodicInterval(minutes)
    }
}
