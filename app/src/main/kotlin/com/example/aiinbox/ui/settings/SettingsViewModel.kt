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
import com.example.aiinbox.work.ModelDownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val modelManager: ModelManager,
    private val driveAuthRepository: DriveAuthRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init { refresh() }

    init {
        _state.update { it.copy(driveAccountEmail = driveAuthRepository.currentEmail()) }
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
}
