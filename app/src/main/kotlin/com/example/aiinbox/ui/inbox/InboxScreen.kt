package com.example.aiinbox.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.aiinbox.R
import com.example.aiinbox.data.db.Attachment
import com.example.aiinbox.data.db.InboxItemWithAttachments
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
            var fieldValue by remember { mutableStateOf(TextFieldValue(state.filter.query)) }
            // Re-sync if the query is reset from outside the field (e.g. a future
            // clear-filter button). Cheap to keep here even before such a button
            // exists — it's the seam that makes adding one trivial.
            LaunchedEffect(state.filter.query) {
                if (state.filter.query != fieldValue.text) {
                    fieldValue = fieldValue.copy(text = state.filter.query)
                }
            }
            OutlinedTextField(
                value = fieldValue,
                onValueChange = { newValue ->
                    fieldValue = newValue
                    // Only forward to the search backend once the IME has no
                    // unconfirmed composition range. ASCII typing leaves
                    // composition == null on every keystroke so real-time search
                    // for English is preserved.
                    if (newValue.composition == null) {
                        viewModel.onQueryChanged(newValue.text)
                    }
                },
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
                    items(state.items, key = { it.item.id }) { wrap ->
                        InboxItemCard(wrap = wrap, onClick = { onItemClick(wrap.item.id) })
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
private fun InboxItemCard(wrap: InboxItemWithAttachments, onClick: () -> Unit) {
    val item = wrap.item
    val attachments = wrap.attachments.sortedBy { it.ordering }
    val cardColors = if (item.status == ItemStatus.FAILED) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(modifier = Modifier.clickable { onClick() }, colors = cardColors) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (attachments.isNotEmpty()) {
                    AttachmentThumbnails(attachments)
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(
                        text = item.title?.takeIf { it.isNotBlank() }
                            ?: item.originalText?.take(40)?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.inbox_pending_placeholder),
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
    }
}

@Composable
private fun AttachmentThumbnails(atts: List<Attachment>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val visible = atts.take(2)
        visible.forEachIndexed { idx, att ->
            AsyncImage(
                model = att,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .let { if (idx > 0) it.padding(start = 4.dp) else it },
                contentScale = ContentScale.Crop,
            )
        }
        if (atts.size > 2) {
            Text(
                "+${atts.size - 2}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 4.dp),
            )
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
