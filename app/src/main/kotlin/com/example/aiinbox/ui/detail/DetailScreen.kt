package com.example.aiinbox.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiinbox.R
import com.example.aiinbox.calendar.CalendarIntentBuilder
import com.example.aiinbox.data.db.ExtractedEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.item?.title ?: "詳細") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
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
                        originalTextSnippet = item.originalText.take(500),
                    )
                    ctx.startActivity(intent)
                }
            }

            item.summary?.takeIf { it.isNotBlank() }?.let {
                Card { Text(it, modifier = Modifier.padding(12.dp)) }
            }

            if (item.tags.isNotEmpty() || item.people.isNotEmpty() || item.places.isNotEmpty() || item.category != null) {
                Card {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.category?.let { Text("カテゴリ: $it") }
                        if (item.tags.isNotEmpty()) Text("タグ: " + item.tags.joinToString())
                        if (item.people.isNotEmpty()) Text("人物: " + item.people.joinToString())
                        if (item.places.isNotEmpty()) Text("場所: " + item.places.joinToString())
                    }
                }
            }

            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("原文", modifier = Modifier.padding(bottom = 4.dp))
                    Text(item.originalText)
                }
            }
        }
    }
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
                Text("開始: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it)))
            }
            event.location?.let { Text("場所: $it") }
            Button(onClick = onAddToCalendar) {
                Text(stringResource(R.string.add_to_calendar))
            }
        }
    }
}
