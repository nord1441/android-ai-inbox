package com.example.aiinbox.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    val activity = remember(ctx) { ctx.findActivity() }
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
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
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.settings_drive_section),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (s.driveAccountEmail == null) {
                        Text(stringResource(R.string.settings_drive_unlinked))
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { activity?.let(viewModel::onLinkDriveClicked) },
                            enabled = !s.isLinkingInProgress && activity != null,
                        ) { Text(stringResource(R.string.settings_drive_link_button)) }
                    } else {
                        Text(stringResource(R.string.settings_drive_linked_as, s.driveAccountEmail!!))
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = viewModel::onUnlinkDriveClicked) {
                            Text(stringResource(R.string.settings_drive_unlink_button))
                        }
                    }
                    s.driveLinkError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.settings_drive_error, it),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
