package uk.nordtek.aiinbox.ui.modeldownload

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import uk.nordtek.aiinbox.data.crypto.HfTokenStore
import uk.nordtek.aiinbox.llm.ModelManager
import uk.nordtek.aiinbox.llm.RamDetector
import uk.nordtek.aiinbox.work.ModelDownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelDownloadViewModel @Inject constructor(
    application: Application,
    private val modelManager: ModelManager,
    private val hfTokenStore: HfTokenStore,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(
        ModelDownloadUiState(
            variant = RamDetector.selectVariantForDevice(application),
            hasHfToken = hfTokenStore.hasToken(),
        )
    )
    val state: StateFlow<ModelDownloadUiState> = _state.asStateFlow()

    fun onTokenEntered(token: String) {
        if (token.isBlank()) {
            hfTokenStore.clear()
            _state.value = _state.value.copy(hasHfToken = false)
        } else {
            hfTokenStore.set(token)
            _state.value = _state.value.copy(hasHfToken = true, errorMessage = null)
        }
    }

    fun onStartClicked() {
        if (!isOnUnmeteredNetwork()) {
            _state.value = _state.value.copy(showMobileNetworkWarning = true)
            return
        }
        startDownload()
    }

    fun onProceedAnyway() {
        _state.value = _state.value.copy(showMobileNetworkWarning = false)
        startDownload()
    }

    fun onCancelMobileWarning() {
        _state.value = _state.value.copy(showMobileNetworkWarning = false)
    }

    private fun startDownload() {
        val variant = _state.value.variant
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(Data.Builder().putString(ModelDownloadWorker.KEY_VARIANT, variant.name).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        val wm = WorkManager.getInstance(getApplication())
        wm.enqueueUniqueWork("model_dl_${variant.name}", androidx.work.ExistingWorkPolicy.KEEP, request)

        viewModelScope.launch {
            wm.getWorkInfoByIdFlow(request.id).collect { info ->
                if (info == null) return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        val downloaded = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED, 0)
                        val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, modelManager.expectedSizeBytes(variant))
                        _state.value = _state.value.copy(
                            isDownloading = true,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            errorMessage = null,
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _state.value = _state.value.copy(isDownloading = false, isCompleted = true)
                    }
                    WorkInfo.State.FAILED -> {
                        _state.value = _state.value.copy(
                            isDownloading = false,
                            errorMessage = info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "DL失敗",
                        )
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun isOnUnmeteredNetwork(): Boolean {
        val cm = getApplication<Application>().getSystemService<ConnectivityManager>() ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
