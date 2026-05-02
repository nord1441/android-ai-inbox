package com.example.aiinbox.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiinbox.R
import com.example.aiinbox.data.db.InboxItem
import com.example.aiinbox.data.db.ItemStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onItemClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Inbox") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            OutlinedTextField(
                value = state.filter.query,
                onValueChange = viewModel::onQueryChanged,
                placeholder = { Text(stringResource(R.string.inbox_search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            FilterChipsRow(state, viewModel)

            if (state.items.isEmpty() && !state.loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (state.filter.isEmpty)
                            stringResource(R.string.inbox_empty)
                        else stringResource(R.string.inbox_no_matches)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        InboxItemCard(item = item, onClick = { onItemClick(item.id) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipsRow(state: InboxUiState, vm: InboxViewModel) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = state.filter.hasEventOnly,
                onClick = { vm.onHasEventToggled() },
                label = { Text("📅 " + stringResource(R.string.filter_has_event)) },
            )
        }
        items(state.availableCategories.toList()) { cat ->
            FilterChip(
                selected = cat in state.filter.categories,
                onClick = { vm.onCategoryToggled(cat) },
                label = { Text(cat) },
            )
        }
        items(state.availableTags.toList()) { tag ->
            FilterChip(
                selected = tag in state.filter.tags,
                onClick = { vm.onTagToggled(tag) },
                label = { Text("#$tag") },
            )
        }
    }
}

@Composable
private fun InboxItemCard(item: InboxItem, onClick: () -> Unit) {
    val cardColors = if (item.status == ItemStatus.FAILED) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(modifier = Modifier.clickable { onClick() }, colors = cardColors) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = item.title ?: item.originalText.take(40),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.summary ?: stringResource(R.string.inbox_pending_placeholder),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                if (item.status == ItemStatus.PENDING || item.status == ItemStatus.PROCESSING) {
                    StatusChip(stringResource(R.string.status_processing))
                }
                if (item.status == ItemStatus.FAILED) StatusChip(stringResource(R.string.status_failed))
                if (item.event != null) StatusChip("📅")
                item.category?.let { StatusChip(it) }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String) {
    AssistChip(
        onClick = {},
        label = { Text(text) },
        colors = AssistChipDefaults.assistChipColors(),
    )
}
