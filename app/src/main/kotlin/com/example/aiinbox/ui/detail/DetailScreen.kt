package com.example.aiinbox.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiinbox.R
import com.example.aiinbox.calendar.CalendarIntentBuilder
import com.example.aiinbox.data.db.Attachment
import com.example.aiinbox.data.db.ExtractedEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showReprocessDialog by remember { mutableStateOf(false) }
    var viewerIndex: Int? by remember { mutableStateOf(null) }

    // Snackbar for delete + undo
    LaunchedEffect(state.deleted) {
        if (state.deleted) {
            val res = snackbar.showSnackbar(
                message = ctx.getString(R.string.snackbar_deleted),
                actionLabel = ctx.getString(R.string.action_undo),
            )
            when (res) {
                SnackbarResult.ActionPerformed -> viewModel.onUndoDelete()
                SnackbarResult.Dismissed -> onBack()
            }
        }
    }

    // Surface error to snackbar — but not while the delete-undo snackbar is
    // showing, since enqueuing here would replace it (Dismissed fires
    // immediately and would prematurely call onBack).
    LaunchedEffect(state.errorMessage, state.deleted) {
        if (!state.deleted) {
            state.errorMessage?.let {
                snackbar.showSnackbar(it)
                viewModel.clearError()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(state.item?.title ?: stringResource(R.string.detail_title_default))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showReprocessDialog = true }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_reprocess),
                        )
                    }
                    IconButton(onClick = { viewModel.onDelete() }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val item = state.item ?: return@Scaffold

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item.event?.let { ev ->
                EventCard(event = ev) {
                    val intent = CalendarIntentBuilder.build(
                        event = ev,
                        summary = item.summary,
                        originalTextSnippet = item.originalText?.take(500) ?: "",
                    )
                    ctx.startActivity(intent)
                }
            }

            AttachmentGallery(
                atts = state.attachments,
                onAttachmentClick = { viewerIndex = it },
            )

            EditableField(
                label = stringResource(R.string.detail_field_title),
                value = item.title ?: "",
                onCommit = { viewModel.onEditField("title", it) },
            )
            EditableField(
                label = stringResource(R.string.detail_field_summary),
                value = item.summary ?: "",
                onCommit = { viewModel.onEditField("summary", it) },
                singleLine = false,
            )
            EditableField(
                label = stringResource(R.string.detail_field_category),
                value = item.category ?: "",
                onCommit = { viewModel.onEditField("category", it) },
            )
            EditableListField(
                label = stringResource(R.string.detail_field_tags),
                values = item.tags,
                onCommit = { viewModel.onEditListField("tags", it) },
            )
            EditableListField(
                label = stringResource(R.string.detail_field_people),
                values = item.people,
                onCommit = { viewModel.onEditListField("people", it) },
            )
            EditableListField(
                label = stringResource(R.string.detail_field_places),
                values = item.places,
                onCommit = { viewModel.onEditListField("places", it) },
            )

            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.detail_section_original),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Text(item.originalText ?: "")
                }
            }
        }
    }

    viewerIndex?.let { idx ->
        FullscreenViewer(
            atts = state.attachments,
            initialIndex = idx,
            onDismiss = { viewerIndex = null },
        )
    }

    if (showReprocessDialog) {
        AlertDialog(
            onDismissRequest = { showReprocessDialog = false },
            title = { Text(stringResource(R.string.confirm_reprocess_title)) },
            text = { Text(stringResource(R.string.confirm_reprocess_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showReprocessDialog = false
                    viewModel.onReprocess()
                }) { Text(stringResource(R.string.action_reprocess)) }
            },
            dismissButton = {
                TextButton(onClick = { showReprocessDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun EditableField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
    singleLine: Boolean = true,
) {
    // Local edit buffer; reset whenever the canonical [value] changes externally
    // (e.g. after re-summarization while focus was elsewhere).
    var local by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = local,
        onValueChange = { local = it },
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                if (!focus.isFocused && local != value) {
                    onCommit(local)
                }
            },
    )
}

@Composable
private fun EditableListField(
    label: String,
    values: List<String>,
    onCommit: (List<String>) -> Unit,
) {
    val joined = values.joinToString(", ")
    var local by remember(joined) { mutableStateOf(joined) }
    OutlinedTextField(
        value = local,
        onValueChange = { local = it },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                if (!focus.isFocused && local != joined) {
                    onCommit(
                        local.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    )
                }
            },
    )
}

@Composable
private fun EventCard(event: ExtractedEvent, onAddToCalendar: () -> Unit) {
    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("📅 " + event.title)
            event.startMillis?.let {
                Text(
                    stringResource(
                        R.string.detail_event_starts_at,
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)),
                    )
                )
            }
            event.location?.let {
                Text(stringResource(R.string.detail_event_location, it))
            }
            if (event.confidence < 0.6f) {
                Text(stringResource(R.string.detail_event_low_confidence))
            }
            Button(onClick = onAddToCalendar) {
                Text(stringResource(R.string.add_to_calendar))
            }
        }
    }
}

@Composable
private fun AttachmentGallery(
    atts: List<Attachment>,
    onAttachmentClick: (Int) -> Unit,
) {
    if (atts.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(atts.sortedBy { it.ordering }) { idx, att ->
            coil.compose.AsyncImage(
                model = att,
                contentDescription = null,
                modifier = Modifier
                    .size(120.dp)
                    .clickable { onAttachmentClick(idx) },
                contentScale = ContentScale.Crop,
            )
        }
    }
    var ocrExpanded by remember { mutableStateOf(false) }
    TextButton(onClick = { ocrExpanded = !ocrExpanded }) {
        Text(if (ocrExpanded) "OCR テキストを隠す" else "OCR テキストを表示")
    }
    if (ocrExpanded) {
        atts.sortedBy { it.ordering }.forEachIndexed { idx, att ->
            Text(
                "[${idx + 1}] ${att.ocrText ?: "(未抽出)"}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FullscreenViewer(
    atts: List<Attachment>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val pagerState = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = initialIndex,
        ) { atts.size }
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) { page ->
            var scale by remember(page) { mutableStateOf(1f) }
            var offset by remember(page) { mutableStateOf(Offset.Zero) }
            val transformable = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                offset += pan
            }
            coil.compose.AsyncImage(
                model = atts[page],
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y,
                    )
                    .transformable(state = transformable),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
