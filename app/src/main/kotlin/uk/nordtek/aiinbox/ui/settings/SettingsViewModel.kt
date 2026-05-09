package uk.nordtek.aiinbox.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import uk.nordtek.aiinbox.BuildConfig
import uk.nordtek.aiinbox.data.db.FsSyncStateDao
import uk.nordtek.aiinbox.data.db.FsSyncStateEntity
import uk.nordtek.aiinbox.llm.ModelManager
import uk.nordtek.aiinbox.llm.RamDetector
import uk.nordtek.aiinbox.sync.FsSyncCoordinator
import uk.nordtek.aiinbox.sync.FsSyncFolderStore
import uk.nordtek.aiinbox.sync.FsSyncStateRepository
import uk.nordtek.aiinbox.sync.SafFolderAccess
import uk.nordtek.aiinbox.work.ModelDownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val PREF_FS_INTERVAL = "fs_sync_interval_minutes"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val modelManager: ModelManager,
    private val syncStateRepository: FsSyncStateRepository,
    private val syncCoordinator: FsSyncCoordinator,
    private val folderStore: FsSyncFolderStore,
    private val syncStateDao: FsSyncStateDao,
    private val safFolderAccess: SafFolderAccess,
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("ai_inbox_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refresh()
        val storedInterval = prefs.getLong(PREF_FS_INTERVAL, 30L)
        val intervalMinutes = if (storedInterval == -1L) null else storedInterval
        val storedUri = folderStore.get()
        val displayName = storedUri?.let { runCatching { safFolderAccess.displayName(it) }.getOrNull() }
        _state.update {
            it.copy(
                fsSyncFolderUri = storedUri,
                fsSyncFolderName = displayName,
                fsSyncIntervalMinutes = intervalMinutes,
            )
        }
        viewModelScope.launch {
            syncStateRepository.runtime.collect { rt -> _state.update { it.copy(fsSyncRuntime = rt) } }
        }
        viewModelScope.launch {
            syncStateRepository.lastFullSyncAt.collect { ts -> _state.update { it.copy(fsSyncLastFullSyncAt = ts) } }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val variant = modelManager.currentVariant()
            val modelSize = variant?.let { modelManager.modelFilePath(it).length() } ?: 0L
            val dbSize = withContext(Dispatchers.IO) {
                getApplication<Application>().getDatabasePath("inbox.db").length()
            }
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

    fun onFsSyncFolderPicked(uri: String) {
        viewModelScope.launch {
            folderStore.set(uri)
            syncStateDao.upsert(FsSyncStateEntity(id = 1, folderUri = uri, lastFullSyncAt = null))
            val name = runCatching { safFolderAccess.displayName(uri) }.getOrNull()
            _state.update { it.copy(fsSyncFolderUri = uri, fsSyncFolderName = name) }
            // Kick off the first sync immediately.
            syncCoordinator.requestImmediateSync()
            // Re-enroll periodic at the persisted interval.
            syncCoordinator.setPeriodicInterval(_state.value.fsSyncIntervalMinutes)
        }
    }

    fun onFsSyncFolderCleared() {
        viewModelScope.launch {
            folderStore.clear()
            syncStateDao.upsert(FsSyncStateEntity(id = 1, folderUri = null, lastFullSyncAt = null))
            syncCoordinator.setPeriodicInterval(null)
            _state.update { it.copy(fsSyncFolderUri = null, fsSyncFolderName = null, fsSyncLastFullSyncAt = null) }
        }
    }

    fun onFsSyncNowClicked() = syncCoordinator.requestImmediateSync()

    fun onFsSyncIntervalSelected(minutes: Long?) {
        _state.update { it.copy(fsSyncIntervalMinutes = minutes) }
        prefs.edit().putLong(PREF_FS_INTERVAL, minutes ?: -1L).apply()
        syncCoordinator.setPeriodicInterval(minutes)
    }
}
