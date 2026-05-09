package uk.nordtek.aiinbox.ui.modeldownload

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.nordtek.aiinbox.llm.ModelVariant

private const val GEMMA_E2B_LICENSE_URL =
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
private const val GEMMA_E4B_LICENSE_URL =
    "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm"
private const val HF_TOKEN_URL = "https://huggingface.co/settings/tokens"

@Composable
fun ModelDownloadScreen(
    onCompleted: () -> Unit,
    viewModel: ModelDownloadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var tokenInput by remember { mutableStateOf("") }

    LaunchedEffect(state.isCompleted) {
        if (state.isCompleted) onCompleted()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("AI Inboxを使うには初回モデルのダウンロードが必要です")
            Spacer(Modifier.height(8.dp))
            Text("選択モデル: ${state.variant.name}")
            Text("サイズ目安: ${(state.totalBytes / 1024 / 1024).coerceAtLeast(0)} MB")
            Spacer(Modifier.height(16.dp))

            if (!state.hasHfToken) {
                HfTokenSetup(
                    variant = state.variant,
                    tokenInput = tokenInput,
                    onTokenInputChange = { tokenInput = it },
                    onSaveToken = {
                        viewModel.onTokenEntered(tokenInput)
                        tokenInput = ""
                    },
                    onOpenLicensePage = {
                        val url = when (state.variant) {
                            ModelVariant.GEMMA_4_E2B -> GEMMA_E2B_LICENSE_URL
                            ModelVariant.GEMMA_4_E4B -> GEMMA_E4B_LICENSE_URL
                            else -> GEMMA_E2B_LICENSE_URL
                        }
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    onOpenTokenPage = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HF_TOKEN_URL)))
                    },
                )
            } else {
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
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { viewModel.onTokenEntered("") }) {
                    Text("HFトークンをクリア")
                }
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

@Composable
private fun HfTokenSetup(
    variant: ModelVariant,
    tokenInput: String,
    onTokenInputChange: (String) -> Unit,
    onSaveToken: () -> Unit,
    onOpenLicensePage: () -> Unit,
    onOpenTokenPage: () -> Unit,
) {
    Text(
        "Gemma 4 のダウンロードには Hugging Face の認証トークンが必要です。",
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text("手順:")
    Text("1. ライセンスを承諾（ブラウザ）")
    Text("2. アクセストークンを発行（ブラウザ）")
    Text("3. トークンを下に貼り付けて保存")
    Spacer(Modifier.height(12.dp))

    Button(onClick = onOpenLicensePage) {
        Text("1. ${variant.name} のライセンスページを開く")
    }
    Spacer(Modifier.height(4.dp))
    Button(onClick = onOpenTokenPage) {
        Text("2. HFトークン作成ページを開く")
    }
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = tokenInput,
        onValueChange = onTokenInputChange,
        label = { Text("HF Access Token (hf_...)") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onSaveToken,
        enabled = tokenInput.isNotBlank(),
    ) {
        Text("3. トークンを保存して次へ")
    }
}
