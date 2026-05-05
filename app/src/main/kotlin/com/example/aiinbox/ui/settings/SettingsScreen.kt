package com.example.aiinbox.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiinbox.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val safPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Persist read+write permission across reboots / process death.
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.onFsSyncFolderPicked(uri.toString())
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(R.string.settings_model_status))
                    Text(
                        stringResource(
                            R.string.settings_model_variant,
                            s.currentVariant?.name ?: stringResource(R.string.settings_variant_none),
                        )
                    )
                    Text(
                        stringResource(
                            R.string.settings_model_size,
                            s.modelSizeBytes / 1024 / 1024,
                        )
                    )
                    Button(onClick = viewModel::onRedownload) {
                        Text(stringResource(R.string.settings_model_redownload))
                    }
                }
            }
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.settings_db_size))
                    Text(stringResource(R.string.settings_db_size_value, s.dbSizeBytes / 1024))
                }
            }
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.settings_version))
                    Text(s.versionName)
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.settings_fs_sync_section),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (s.fsSyncFolderUri == null) {
                        Text(stringResource(R.string.settings_fs_sync_unconfigured))
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { safPickerLauncher.launch(null) }) {
                            Text(stringResource(R.string.settings_fs_sync_pick_folder))
                        }
                    } else {
                        Text(stringResource(R.string.settings_fs_sync_folder, s.fsSyncFolderName ?: s.fsSyncFolderUri!!))
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(onClick = { safPickerLauncher.launch(null) }) {
                                Text(stringResource(R.string.settings_fs_sync_change_folder))
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = viewModel::onFsSyncFolderCleared) {
                                Text(stringResource(R.string.settings_fs_sync_clear_folder))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        val statusText = when (val rt = s.fsSyncRuntime) {
                            com.example.aiinbox.sync.FsSyncState.Idle ->
                                stringResource(R.string.settings_fs_sync_status_idle)
                            com.example.aiinbox.sync.FsSyncState.Running ->
                                stringResource(R.string.settings_fs_sync_status_running)
                            is com.example.aiinbox.sync.FsSyncState.Error ->
                                stringResource(R.string.settings_fs_sync_status_error, rt.message ?: "")
                        }
                        Text(statusText)
                        s.fsSyncLastFullSyncAt?.let {
                            Text(stringResource(R.string.settings_fs_sync_last_sync, formatTimestamp(it)))
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = viewModel::onFsSyncNowClicked,
                            enabled = s.fsSyncRuntime !is com.example.aiinbox.sync.FsSyncState.Running,
                        ) { Text(stringResource(R.string.settings_fs_sync_now)) }
                        Spacer(Modifier.height(8.dp))
                        FsSyncIntervalDropdown(
                            current = s.fsSyncIntervalMinutes,
                            onSelected = viewModel::onFsSyncIntervalSelected,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FsSyncIntervalDropdown(current: Long?, onSelected: (Long?) -> Unit) {
    val options: List<Pair<Long?, String>> = listOf(
        15L to "15分", 30L to "30分", 60L to "1時間", 360L to "6時間", null to "自動のみ",
    )
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == current }?.second ?: "30分"
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.settings_fs_sync_interval_label, label))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (m, optionLabel) ->
                DropdownMenuItem(text = { Text(optionLabel) }, onClick = { onSelected(m); expanded = false })
            }
        }
    }
}

private fun formatTimestamp(epochMs: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMs))
