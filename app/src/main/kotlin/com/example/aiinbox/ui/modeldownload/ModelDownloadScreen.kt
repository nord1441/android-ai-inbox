package com.example.aiinbox.ui.modeldownload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ModelDownloadScreen(
    onCompleted: () -> Unit,
    viewModel: ModelDownloadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.isCompleted) {
        if (state.isCompleted) onCompleted()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("AI Inboxを使うには初回モデルのダウンロードが必要です")
            Spacer(Modifier.height(8.dp))
            Text("選択モデル: ${state.variant.name}")
            Text("サイズ目安: ${(state.totalBytes / 1024 / 1024).coerceAtLeast(0)} MB")
            Spacer(Modifier.height(16.dp))
            if (state.isDownloading || state.totalBytes > 0) {
                val ratio = if (state.totalBytes > 0)
                    state.downloadedBytes.toFloat() / state.totalBytes else 0f
                LinearProgressIndicator(progress = { ratio })
                Spacer(Modifier.height(8.dp))
                Text("${state.downloadedBytes / 1024 / 1024} / ${state.totalBytes / 1024 / 1024} MB")
            }
            state.errorMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text("エラー: $it")
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.onStartClicked() },
                enabled = !state.isDownloading && state.canStart,
            ) {
                Text(if (state.isDownloading) "ダウンロード中…" else "ダウンロードを開始")
            }
        }
    }

    if (state.showMobileNetworkWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.onCancelMobileWarning() },
            title = { Text("モバイル通信での大容量DL") },
            text = { Text("Wi-Fiに接続されていません。${state.totalBytes / 1024 / 1024}MB をモバイル通信でダウンロードしますか？") },
            confirmButton = {
                TextButton(onClick = { viewModel.onProceedAnyway() }) { Text("続行") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onCancelMobileWarning() }) { Text("キャンセル") }
            },
        )
    }
}
