package com.example.aiinbox.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiinbox.data.repository.InboxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repository: InboxRepository,
) : ViewModel() {

    private val filterState = MutableStateFlow(InboxFilter())

    // Pair items with the filter that produced them so combine never emits
    // (oldItems, newFilter): when filterState changes, this inner flow
    // re-subscribes and `items` and `filter` always travel together.
    private val itemsWithFilterFlow = filterState
        .flatMapLatest { filter ->
            repository.observeFilteredWithAttachments(filter).map { items -> items to filter }
        }

    private val allItemsFlow = repository.observeAll()

    val uiState: StateFlow<InboxUiState> = combine(
        itemsWithFilterFlow,
        allItemsFlow,
    ) { (items, filter), allItems ->
        InboxUiState(
            items = items,
            loading = false,
            filter = filter,
            availableCategories = allItems.mapNotNull { it.category }
                .filter { it.isNotBlank() }
                .toSet(),
            availableTags = allItems.flatMap { it.tags }
                .filter { it.isNotBlank() }
                .toSet(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InboxUiState(loading = true),
    )

    fun onQueryChanged(query: String) {
        filterState.value = filterState.value.copy(query = query)
    }

    fun onCategoryToggled(category: String) {
        val cur = filterState.value.categories
        filterState.value = filterState.value.copy(
            categories = if (category in cur) cur - category else cur + category
        )
    }

    fun onTagToggled(tag: String) {
        val cur = filterState.value.tags
        filterState.value = filterState.value.copy(
            tags = if (tag in cur) cur - tag else cur + tag
        )
    }

    fun onHasEventToggled() {
        filterState.value = filterState.value.copy(hasEventOnly = !filterState.value.hasEventOnly)
    }

    fun onClearFilter() {
        filterState.value = InboxFilter()
    }
}
