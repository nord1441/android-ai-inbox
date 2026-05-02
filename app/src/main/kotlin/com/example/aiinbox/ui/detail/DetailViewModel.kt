package com.example.aiinbox.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: InboxRepository,
    private val workScheduler: WorkScheduler,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val itemId: String = checkNotNull(savedStateHandle[NAV_ARG_ID])
    private val deletedFlow = MutableStateFlow(false)
    private val errorFlow = MutableStateFlow<String?>(null)
    private var pendingDeleteJob: Job? = null

    val uiState: StateFlow<DetailUiState> = combine(
        repository.observeById(itemId),
        deletedFlow,
        errorFlow,
    ) { item, deleted, err ->
        DetailUiState(
            item = item,
            loading = item == null && !deleted,
            deleted = deleted,
            errorMessage = err,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DetailUiState(loading = true),
    )

    fun onEditField(field: String, value: String?) {
        viewModelScope.launch {
            try {
                repository.updateField(itemId, field, value)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                errorFlow.value = t.message
            }
        }
    }

    fun onEditListField(field: String, values: List<String>) {
        viewModelScope.launch {
            try {
                repository.updateListField(itemId, field, values)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                errorFlow.value = t.message
            }
        }
    }

    fun onReprocess() {
        viewModelScope.launch {
            workScheduler.enqueueSummarize(itemId)
        }
    }

    fun onDelete() {
        // Cancel any earlier delete cycle so its delayed finalize cannot
        // wipe the buffer entry for THIS new delete cycle.
        pendingDeleteJob?.cancel()
        pendingDeleteJob = viewModelScope.launch {
            if (repository.softDelete(itemId)) {
                deletedFlow.value = true
                delay(UNDO_WINDOW_MS)
                if (deletedFlow.value) {
                    repository.finalizeDelete(itemId)
                }
            }
        }
    }

    fun onUndoDelete() {
        // Cancel the pending finalize so it does not run after we've restored.
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        viewModelScope.launch {
            if (repository.restoreDeleted(itemId)) {
                deletedFlow.value = false
            }
        }
    }

    fun clearError() {
        errorFlow.value = null
    }

    companion object {
        const val NAV_ARG_ID = "id"
        private const val UNDO_WINDOW_MS = 5_000L
    }
}
