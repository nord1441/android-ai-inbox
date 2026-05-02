# Android AI Inbox — Plan 2: Real LLM (MediaPipe + Gemma 4) 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Plan 1で構築した `FakeLlmEngine` ベースのMVPに、**MediaPipe LLM Inference API + Gemma 4 (E2B / E4B)** による実推論を組み込む。アイドルタイムアウト付き Foreground Service でモデル寿命を管理し、初回起動時に端末RAMに応じて適切なモデルをダウンロードする。

**Architecture:** `LlmInferenceService` (Foreground Service) がモデルをジョブキュー駆動でロード・推論し、5分アイドルでアンロード。`SummarizeWorker` は Service にbindしてジョブを投入。`ModelDownloadWorker` がHugging FaceからGemma 4の `.task` ファイルをDLする（Range-GETでレジューム可）。

**Tech Stack:** 既存 (Plan 1) + `com.google.mediapipe:tasks-genai`, `okhttp3` (DL用), Foreground Service, AndroidX Activity Result API.

**前提:** Plan 1 が完了し、すべてのテストがパスしている状態。

**スペックリンク:** [`docs/superpowers/specs/2026-05-02-android-ai-inbox-design.md`](../specs/2026-05-02-android-ai-inbox-design.md)

---

## このPlanの完成条件（Definition of Done）

1. ✅ アプリ初回起動時、モデルがなければ `ModelDownloadScreen` が表示される
2. ✅ 端末RAMが8GB以上なら Gemma 4 E4B (~2.5GB)、6〜7GBなら E2B (~1.3GB) を自動選択
3. ✅ Wi-Fi未接続時はモバイル通信での大容量DL警告ダイアログが表示される
4. ✅ DL中はForeground Notificationで進捗が見える
5. ✅ DL完了後、メイン画面（Inbox）へ遷移
6. ✅ Share Intentからのテキストが、実Gemma 4で要約・情報抽出される
7. ✅ 連続Share時、`LlmInferenceService` がモデルを保持したまま順次処理（モデル再ロードなし）
8. ✅ 5分間ジョブが来なければ `LlmInferenceService` が `stopSelf()` してRAMを解放
9. ✅ 推論失敗時（OOM/タイムアウト）はコンテキスト半減で1回リトライ、それでも失敗なら `FAILED` ステータス
10. ✅ 設定画面なしでもDL状態がわかる（DL進捗 / 完了通知）
11. ✅ Plan 1の全テストが引き続きパス、Plan 2新規テストもパス

**このPlanのスコープ外**（Plan 3で対応）:
- 検索バー・フィルタチップUI
- 詳細画面の編集・再要約・削除（5秒Undo）
- 設定画面（モデル状態・再DL・DB使用量）
- イベント検出時のアクションボタン付き通知
- 通知グルーピング
- 空状態・エラー状態の洗練

---

## ファイル構成（Plan 2で作成・編集するファイル）

```
android-ai-inbox/
├── app/
│   ├── build.gradle.kts                               [編集] (MediaPipe + OkHttp追加)
│   ├── src/main/
│   │   ├── AndroidManifest.xml                        [編集] (Service宣言、FOREGROUND_SERVICE)
│   │   ├── kotlin/com/example/aiinbox/
│   │   │   ├── MainActivity.kt                        [編集] (model check)
│   │   │   ├── llm/
│   │   │   │   ├── ModelManager.kt                    [新規]
│   │   │   │   ├── MediaPipeLlmEngine.kt              [新規]
│   │   │   │   ├── LlmInferenceService.kt             [新規]
│   │   │   │   ├── LlmServiceClient.kt                [新規] (bind API)
│   │   │   │   └── RamDetector.kt                     [新規]
│   │   │   ├── work/
│   │   │   │   ├── SummarizeWorker.kt                 [編集] (Service経由に切替)
│   │   │   │   ├── ModelDownloadWorker.kt             [新規]
│   │   │   │   └── ModelDownloadProgress.kt           [新規]
│   │   │   ├── ui/modeldownload/
│   │   │   │   ├── ModelDownloadViewModel.kt          [新規]
│   │   │   │   └── ModelDownloadScreen.kt             [新規]
│   │   │   ├── ui/navigation/Routes.kt                [編集]
│   │   │   ├── notification/NotificationHelper.kt     [編集] (DL進捗用)
│   │   │   └── di/
│   │   │       ├── LlmModule.kt                       [編集] (Fake→MediaPipe)
│   │   │       └── NetworkModule.kt                   [新規] (OkHttp)
│   │   ├── res/values/strings.xml                     [編集]
│   ├── src/test/...                                   [新規 多数]
│   └── src/androidTest/...                            [新規 多数]
└── app/proguard-rules.pro                             [編集] (MediaPipe / OkHttp)
```

---

## Task 1: MediaPipe + OkHttp 依存追加

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/proguard-rules.pro`

- [ ] **Step 1: `gradle/libs.versions.toml` に追記**

`[versions]` に：
```toml
mediapipeTasksGenai = "0.10.20"
okhttp = "4.12.0"
```

`[libraries]` に：
```toml
mediapipe-tasks-genai = { module = "com.google.mediapipe:tasks-genai", version.ref = "mediapipeTasksGenai" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
```

**Note**: `mediapipeTasksGenai` のバージョンは2026年5月時点の最新stableに調整。`./gradlew :app:dependencies | grep mediapipe` で実際に解決されるバージョンを確認のこと。

- [ ] **Step 2: `app/build.gradle.kts` に追記**

`dependencies { ... }` 内：
```kotlin
implementation(libs.mediapipe.tasks.genai)
implementation(libs.okhttp)
testImplementation(libs.okhttp.mockwebserver)
androidTestImplementation(libs.okhttp.mockwebserver)
```

- [ ] **Step 3: `app/proguard-rules.pro` に追記**

```proguard
# MediaPipe
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**

# OkHttp
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
```

- [ ] **Step 4: ビルド確認**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: コミット**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/proguard-rules.pro
git commit -m "build: add MediaPipe Tasks GenAI and OkHttp dependencies"
```

---

## Task 2: RamDetector

**目的:** 端末の搭載RAMを検出して `ModelVariant` を選ぶ。

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/llm/RamDetector.kt`
- Test: `app/src/test/kotlin/com/example/aiinbox/llm/RamDetectorTest.kt`

- [ ] **Step 1: テスト**

```kotlin
package com.example.aiinbox.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RamDetectorTest {
    @Test
    fun `selects E4B for 8GB or higher`() {
        assertThat(RamDetector.selectVariant(totalRamBytes = 8L * 1024 * 1024 * 1024))
            .isEqualTo(ModelVariant.GEMMA_4_E4B)
        assertThat(RamDetector.selectVariant(totalRamBytes = 12L * 1024 * 1024 * 1024))
            .isEqualTo(ModelVariant.GEMMA_4_E4B)
    }

    @Test
    fun `selects E2B for 6 to 8GB`() {
        assertThat(RamDetector.selectVariant(totalRamBytes = 6L * 1024 * 1024 * 1024))
            .isEqualTo(ModelVariant.GEMMA_4_E2B)
        assertThat(RamDetector.selectVariant(totalRamBytes = 7L * 1024 * 1024 * 1024))
            .isEqualTo(ModelVariant.GEMMA_4_E2B)
    }

    @Test
    fun `falls back to E2B for under 6GB (best-effort)`() {
        assertThat(RamDetector.selectVariant(totalRamBytes = 4L * 1024 * 1024 * 1024))
            .isEqualTo(ModelVariant.GEMMA_4_E2B)
    }
}
```

- [ ] **Step 2: 実装**

```kotlin
package com.example.aiinbox.llm

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService

object RamDetector {

    fun detectTotalRamBytes(context: Context): Long {
        val am = context.getSystemService<ActivityManager>() ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    fun selectVariant(totalRamBytes: Long): ModelVariant {
        val gigabytes = totalRamBytes / (1024.0 * 1024 * 1024)
        return if (gigabytes >= 8.0) ModelVariant.GEMMA_4_E4B else ModelVariant.GEMMA_4_E2B
    }

    fun selectVariantForDevice(context: Context): ModelVariant =
        selectVariant(detectTotalRamBytes(context))
}
```

- [ ] **Step 3: テスト通過**

```bash
./gradlew :app:testDebugUnitTest --tests com.example.aiinbox.llm.RamDetectorTest
```
Expected: PASS

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/llm/RamDetector.kt \
        app/src/test/kotlin/com/example/aiinbox/llm/RamDetectorTest.kt
git commit -m "feat(llm): add RamDetector for Gemma 4 variant selection"
```

---

## Task 3: ModelManager（モデルファイル管理）

**目的:** `.task` ファイルの保存先パス管理、存在チェック、サイズ確認、削除。

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/llm/ModelManager.kt`
- Test: `app/src/test/kotlin/com/example/aiinbox/llm/ModelManagerTest.kt` (Robolectric)

- [ ] **Step 1: テスト**

```kotlin
package com.example.aiinbox.llm

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModelManagerTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val manager = ModelManager(ctx)

    @Test
    fun `model not present initially`() {
        assertThat(manager.isModelPresent(ModelVariant.GEMMA_4_E2B)).isFalse()
    }

    @Test
    fun `model file path is under no-backup files dir`() {
        val path = manager.modelFilePath(ModelVariant.GEMMA_4_E2B)
        assertThat(path.absolutePath).contains(ctx.noBackupFilesDir.absolutePath)
        assertThat(path.name).endsWith(".task")
    }

    @Test
    fun `delete removes the file`() {
        val path = manager.modelFilePath(ModelVariant.GEMMA_4_E2B)
        path.parentFile?.mkdirs()
        path.writeBytes(ByteArray(16))
        assertThat(manager.isModelPresent(ModelVariant.GEMMA_4_E2B)).isTrue()
        manager.deleteModel(ModelVariant.GEMMA_4_E2B)
        assertThat(manager.isModelPresent(ModelVariant.GEMMA_4_E2B)).isFalse()
    }

    @Test
    fun `currentVariant returns variant whose file exists`() {
        val path = manager.modelFilePath(ModelVariant.GEMMA_4_E4B)
        path.parentFile?.mkdirs()
        path.writeBytes(ByteArray(16))
        assertThat(manager.currentVariant()).isEqualTo(ModelVariant.GEMMA_4_E4B)
    }
}
```

- [ ] **Step 2: 実装**

```kotlin
package com.example.aiinbox.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun modelFilePath(variant: ModelVariant): File {
        val dir = File(context.noBackupFilesDir, "models").apply { mkdirs() }
        return File(dir, modelFileName(variant))
    }

    fun isModelPresent(variant: ModelVariant): Boolean {
        val f = modelFilePath(variant)
        return f.exists() && f.length() > 0
    }

    fun currentVariant(): ModelVariant? {
        return ModelVariant.entries.firstOrNull {
            it != ModelVariant.FAKE && isModelPresent(it)
        }
    }

    fun deleteModel(variant: ModelVariant) {
        modelFilePath(variant).delete()
    }

    fun expectedSizeBytes(variant: ModelVariant): Long = when (variant) {
        ModelVariant.GEMMA_4_E2B -> 1_300_000_000L
        ModelVariant.GEMMA_4_E4B -> 2_500_000_000L
        ModelVariant.FAKE -> 0L
    }

    fun downloadUrl(variant: ModelVariant): String = when (variant) {
        // TODO: 実DL URLは2026年5月時点で確認・変更すること（Hugging Face / Google CDN）
        // 環境変数やBuildConfigで上書きできるようにしておくと便利
        ModelVariant.GEMMA_4_E2B ->
            "https://huggingface.co/google/gemma-4-e2b-it/resolve/main/gemma-4-e2b-it-q4_k_m.task"
        ModelVariant.GEMMA_4_E4B ->
            "https://huggingface.co/google/gemma-4-e4b-it/resolve/main/gemma-4-e4b-it-q4_k_m.task"
        ModelVariant.FAKE -> error("FAKE variant has no URL")
    }

    private fun modelFileName(variant: ModelVariant): String = when (variant) {
        ModelVariant.GEMMA_4_E2B -> "gemma-4-e2b-q4km.task"
        ModelVariant.GEMMA_4_E4B -> "gemma-4-e4b-q4km.task"
        ModelVariant.FAKE -> "fake.task"
    }
}
```

**実装ノート:** `downloadUrl()` のURLはMVP時点で動作確認できるベストエフォートのプレースホルダ。MediaPipe公式の.taskファイル配布元（Google CDN等）が確定したら差し替える。BuildConfigで上書き可能にする拡張は Plan 3 の設定画面と一緒に行う。

- [ ] **Step 3: テスト通過**

```bash
./gradlew :app:testDebugUnitTest --tests com.example.aiinbox.llm.ModelManagerTest
```
Expected: PASS

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/llm/ModelManager.kt \
        app/src/test/kotlin/com/example/aiinbox/llm/ModelManagerTest.kt
git commit -m "feat(llm): add ModelManager for .task file lifecycle"
```

---

## Task 4: MediaPipeLlmEngine

**目的:** `LlmEngine` の本実装。MediaPipe LLM Inference APIを使い、`.task` ファイルから推論。

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/llm/MediaPipeLlmEngine.kt`
- Test: `app/src/androidTest/kotlin/com/example/aiinbox/llm/MediaPipeLlmEngineTest.kt` (`@LargeTest`、実機 + 実モデル必要)

- [ ] **Step 1: 実装**

```kotlin
package com.example.aiinbox.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaPipeLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val promptBuilder: PromptBuilder,
    private val responseParser: LlmResponseParser,
) : LlmEngine {

    private val _isLoaded = MutableStateFlow(false)
    override val isLoaded: StateFlow<Boolean> = _isLoaded

    private var inference: LlmInference? = null
    private var loadedVariant: ModelVariant? = null
    private val mutex = Mutex()

    override suspend fun ensureLoaded(variant: ModelVariant): Unit = mutex.withLock {
        if (_isLoaded.value && loadedVariant == variant) return
        if (_isLoaded.value && loadedVariant != variant) {
            // 別バリアントが乗ってる → 一旦アンロード
            unloadInternal()
        }
        check(modelManager.isModelPresent(variant)) {
            "Model file is not present for $variant"
        }
        val modelPath = modelManager.modelFilePath(variant).absolutePath
        withContext(Dispatchers.IO) {
            val opts = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS_DEFAULT)
                .setTemperature(0.3f)
                .setTopK(40)
                .build()
            inference = LlmInference.createFromOptions(context, opts)
        }
        loadedVariant = variant
        _isLoaded.value = true
    }

    override suspend fun unload(): Unit = mutex.withLock {
        unloadInternal()
    }

    private fun unloadInternal() {
        inference?.close()
        inference = null
        loadedVariant = null
        _isLoaded.value = false
    }

    override suspend fun summarize(text: String, hint: ContentHint): SummarizeResult {
        check(_isLoaded.value) { "ensureLoaded() must be called before summarize()" }
        val prompt = promptBuilder.build(text, hint)

        val rawResponse = withContext(Dispatchers.IO) {
            inference!!.generateResponse(prompt)
        }

        // パース失敗時のフォールバック：1回だけ簡略プロンプトで再試行
        val parsed = responseParser.parse(rawResponse)
        if (parsed != null) return parsed

        val fallbackPrompt = "次のテキストを200文字以内で要約してください。出力は要約文のみ：\n\n${text.take(4000)}"
        val fallback = withContext(Dispatchers.IO) { inference!!.generateResponse(fallbackPrompt) }
        return SummarizeResult(
            title = null,
            summary = fallback.trim().take(200),
            category = null,
            tags = emptyList(),
            people = emptyList(),
            places = emptyList(),
            urls = emptyList(),
            event = null,
        )
    }

    /** OOM/タイムアウト時のリトライ用：コンテキスト半減で再試行 */
    suspend fun summarizeWithReducedContext(text: String, hint: ContentHint): SummarizeResult {
        val truncated = text.take(text.length / 2)
        return summarize(truncated, hint)
    }

    companion object {
        private const val MAX_TOKENS_DEFAULT = 1024
    }
}
```

- [ ] **Step 2: 実機向け統合テスト（`@LargeTest`、CI除外）**

`app/src/androidTest/kotlin/com/example/aiinbox/llm/MediaPipeLlmEngineTest.kt`:
```kotlin
package com.example.aiinbox.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

@LargeTest
@RunWith(AndroidJUnit4::class)
class MediaPipeLlmEngineTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var engine: MediaPipeLlmEngine
    private val variant = ModelVariant.GEMMA_4_E2B

    @Before
    fun setup() {
        val modelManager = ModelManager(ctx)
        assumeTrue(
            "Skipping: model file not present at ${modelManager.modelFilePath(variant)}",
            modelManager.isModelPresent(variant)
        )
        engine = MediaPipeLlmEngine(
            ctx,
            modelManager,
            PromptBuilder(),
            LlmResponseParser(ZoneId.systemDefault()),
        )
    }

    @Test
    fun `summarize returns non-empty title and summary for memo`() = runBlocking {
        engine.ensureLoaded(variant)
        val r = engine.summarize(
            "今日のランチで佐藤さんと渋谷の新しいラーメン屋に行った。次回は来週木曜18時に同じ店で集合予定。",
            ContentHint.MEMO,
        )
        assertThat(r.summary).isNotEmpty()
        assertThat(r.event).isNotNull()  // 来週木曜18時を抽出していることを期待
        engine.unload()
    }
}
```

**実行方法:** モデルファイルを `adb push` で `/data/data/com.example.aiinbox.debug/no_backup/files/models/gemma-4-e2b-q4km.task` に配置してから：
```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.size=large \
  --tests com.example.aiinbox.llm.MediaPipeLlmEngineTest
```
モデルがなければ `assumeTrue` でスキップされる（CI安全）。

- [ ] **Step 3: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/llm/MediaPipeLlmEngine.kt \
        app/src/androidTest/kotlin/com/example/aiinbox/llm/MediaPipeLlmEngineTest.kt
git commit -m "feat(llm): add MediaPipeLlmEngine implementation with @LargeTest integration test"
```

---

## Task 5: LlmInferenceService（Foreground Service骨格 + Manifest登録）

**目的:** モデルライフサイクル管理用のForeground Serviceを定義。本タスクではbind I/Fと最小限の起動/停止のみ。ジョブキューとアイドルタイムアウトは Task 6 で追加。

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/llm/LlmInferenceService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: `strings.xml` に追加**

```xml
<string name="notification_channel_llm_service">LLM処理</string>
<string name="notification_llm_service_running">要約処理中…</string>
<string name="notification_llm_service_idle">モデル待機中</string>
```

- [ ] **Step 2: `LlmInferenceService.kt` を作成（骨格）**

```kotlin
package com.example.aiinbox.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.example.aiinbox.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject

@AndroidEntryPoint
class LlmInferenceService : Service() {

    @Inject lateinit var llmEngine: LlmEngine
    @Inject lateinit var modelManager: ModelManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun service(): LlmInferenceService = this@LlmInferenceService
    }

    data class Job(
        val text: String,
        val hint: ContentHint,
        val deferred: CompletableDeferred<Result<SummarizeResult>>,
    )

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIF_ID,
            buildNotification(getString(R.string.notification_llm_service_idle)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        scope.launch { llmEngine.unload() }.invokeOnCompletion {
            // best-effort
        }
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService<NotificationManager>() ?: return
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_llm_service),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "llm_service"
        private const val NOTIF_ID = 0x10A1
    }
}

// scope.launch を使うためのimport
private fun CoroutineScope.launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
    kotlinx.coroutines.launch(block = block)
}
```

**注:** 最後の拡張関数は不要なら削除してください（既存の `kotlinx.coroutines.launch` がスコープ拡張として直接使える）。

- [ ] **Step 3: `AndroidManifest.xml` を編集**

`<uses-permission>` 群に追加：
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
```

`<application>` 内に追加：
```xml
<service
    android:name=".llm.LlmInferenceService"
    android:foregroundServiceType="dataSync"
    android:exported="false"
    tools:targetApi="34"/>

<!--
    WorkManager の SystemForegroundService に foregroundServiceType を
    付与するため、AGPの manifest merger でライブラリ側宣言を上書きする。
    ModelDownloadWorker.getForegroundInfo() で FOREGROUND_SERVICE_TYPE_DATA_SYNC を
    要求するので、マニフェスト側でも dataSync を宣言する必要がある（Android 14+ 必須）。
    これがないと:
      java.lang.IllegalArgumentException: foregroundServiceType 0x00000001
        is not a subset of foregroundServiceType attribute 0x00000000
        in service element of manifest file
    で初回モデルDL時にクラッシュする。
-->
<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    tools:node="merge"
    tools:replace="android:foregroundServiceType"/>
```

- [ ] **Step 4: ビルド確認**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/llm/LlmInferenceService.kt \
        app/src/main/AndroidManifest.xml \
        app/src/main/res/values/strings.xml
git commit -m "feat(llm): add LlmInferenceService skeleton with foreground notification"
```

---

## Task 6: ジョブキュー + bind API

**目的:** `LlmInferenceService` にジョブキューを追加し、bindしたクライアントからジョブを投入できるようにする。

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/llm/LlmInferenceService.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/llm/LlmServiceClient.kt`
- Test: `app/src/androidTest/kotlin/com/example/aiinbox/llm/LlmInferenceServiceTest.kt`

- [ ] **Step 1: `LlmInferenceService.kt` をジョブキュー対応に変更**

```kotlin
package com.example.aiinbox.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.example.aiinbox.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LlmInferenceService : Service() {

    @Inject lateinit var llmEngine: LlmEngine
    @Inject lateinit var modelManager: ModelManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binder = LocalBinder()
    private val jobQueue = Channel<Job>(capacity = 64)

    data class Job(
        val text: String,
        val hint: ContentHint,
        val variant: ModelVariant,
        val deferred: CompletableDeferred<Result<SummarizeResult>>,
    )

    inner class LocalBinder : Binder() {
        fun submit(job: Job) {
            jobQueue.trySend(job)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIF_ID,
            buildNotification(getString(R.string.notification_llm_service_idle)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch { runQueueLoop() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        jobQueue.close()
        scope.launch { llmEngine.unload() }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runQueueLoop() {
        for (job in jobQueue) {
            try {
                updateNotif(getString(R.string.notification_llm_service_running))
                llmEngine.ensureLoaded(job.variant)
                val result = try {
                    llmEngine.summarize(job.text, job.hint)
                } catch (oom: OutOfMemoryError) {
                    // コンテキスト半減で1回再試行
                    val truncated = job.text.take(job.text.length / 2)
                    llmEngine.summarize(truncated, job.hint)
                }
                job.deferred.complete(Result.success(result))
            } catch (t: Throwable) {
                job.deferred.complete(Result.failure(t))
            } finally {
                updateNotif(getString(R.string.notification_llm_service_idle))
            }
        }
    }

    private fun createChannel() {
        val nm = getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_llm_service),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun updateNotif(text: String) {
        getSystemService<NotificationManager>()?.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "llm_service"
        private const val NOTIF_ID = 0x10A1
    }
}
```

- [ ] **Step 2: `LlmServiceClient.kt` を作成（bindラッパ）**

```kotlin
package com.example.aiinbox.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LlmServiceClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun submit(text: String, hint: ContentHint, variant: ModelVariant): Result<SummarizeResult> {
        val deferred = CompletableDeferred<Result<SummarizeResult>>()
        val binder = bind() ?: return Result.failure(IllegalStateException("Service bind failed"))
        try {
            binder.submit(LlmInferenceService.Job(text, hint, variant, deferred))
            return deferred.await()
        } finally {
            unbind()
        }
    }

    private var connection: ServiceConnection? = null

    private suspend fun bind(): LlmInferenceService.LocalBinder? = suspendCancellableCoroutine { cont ->
        val intent = Intent(context, LlmInferenceService::class.java)
        // ForegroundServiceとして起動
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? LlmInferenceService.LocalBinder
                cont.resume(binder)
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        connection = conn
        val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        if (!ok) cont.resume(null)
    }

    private fun unbind() {
        connection?.let { context.unbindService(it) }
        connection = null
    }
}
```

- [ ] **Step 3: AndroidTest（実機/エミュレータ）**

```kotlin
package com.example.aiinbox.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LlmInferenceServiceTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var client: LlmServiceClient

    @Test
    fun `submitting a job returns success with FakeLlmEngine bound`() = runBlocking {
        hiltRule.inject()
        val r = client.submit(
            "テスト本文",
            ContentHint.MEMO,
            ModelVariant.FAKE,
        )
        assertThat(r.isSuccess).isTrue()
        assertThat(r.getOrNull()?.summary).isNotEmpty()
    }
}
```

**Note:** このテストは Plan 2 の Task 19（LlmModule差し替え）以降では `MediaPipeLlmEngine` がbindされるため、Fakeを使いたい時はテスト用Hiltモジュールで上書きが必要。Plan 2タスク中は Fake のままでサービス挙動を確認。

- [ ] **Step 4: テスト実行**

```bash
./gradlew :app:connectedDebugAndroidTest --tests com.example.aiinbox.llm.LlmInferenceServiceTest
```
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/llm/LlmInferenceService.kt \
        app/src/main/kotlin/com/example/aiinbox/llm/LlmServiceClient.kt \
        app/src/androidTest/kotlin/com/example/aiinbox/llm/LlmInferenceServiceTest.kt
git commit -m "feat(llm): add job queue and bind API to LlmInferenceService"
```

---

## Task 7: アイドルタイムアウト（5分でアンロード + stopSelf）

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/llm/LlmInferenceService.kt`

- [ ] **Step 1: `runQueueLoop()` の周辺にアイドルタイムアウトロジックを追加**

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

// クラス内のフィールド
private var idleTimerJob: kotlinx.coroutines.Job? = null

// runQueueLoop() を以下に置き換え
private suspend fun runQueueLoop() {
    while (scope.isActive) {
        idleTimerJob?.cancel()
        val job = receiveJobOrTimeout() ?: break  // タイムアウト → break
        idleTimerJob?.cancel()
        try {
            updateNotif(getString(R.string.notification_llm_service_running))
            llmEngine.ensureLoaded(job.variant)
            val result = try {
                llmEngine.summarize(job.text, job.hint)
            } catch (oom: OutOfMemoryError) {
                val truncated = job.text.take(job.text.length / 2)
                llmEngine.summarize(truncated, job.hint)
            }
            job.deferred.complete(Result.success(result))
        } catch (t: Throwable) {
            job.deferred.complete(Result.failure(t))
        } finally {
            updateNotif(getString(R.string.notification_llm_service_idle))
            startIdleTimer()
        }
    }
    // タイムアウトでループ抜けた → アンロード + stopSelf
    llmEngine.unload()
    stopSelf()
}

/** ジョブキューからジョブを取得、IDLE_TIMEOUT_MS でタイムアウトしたらnullを返す */
private suspend fun receiveJobOrTimeout(): Job? {
    return select {
        jobQueue.onReceiveCatching { result ->
            result.getOrNull()
        }
        kotlinx.coroutines.flow.flow<Unit> {
            delay(IDLE_TIMEOUT_MS)
            emit(Unit)
        }.let { /* 不要 */ null }
        // 上の flow 経路は使わない、kotlinx.coroutines.delay + onTimeout に切替
    }
}
```

**修正:** `select { ... }` で `onTimeout` を使う方が自然です。以下のように書き直してください：

```kotlin
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private suspend fun receiveJobOrTimeout(): Job? = select {
    jobQueue.onReceiveCatching { result -> result.getOrNull() }
    onTimeout(IDLE_TIMEOUT_MS) { null }
}

private fun startIdleTimer() {
    // デバッグ・通知用。実際のタイムアウトは receiveJobOrTimeout が担当。
}

companion object {
    private const val CHANNEL_ID = "llm_service"
    private const val NOTIF_ID = 0x10A1
    private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L  // 5分
}
```

- [ ] **Step 2: AndroidTest にタイムアウト挙動の検証を追加**

`LlmInferenceServiceTest.kt` に追加：
```kotlin
@Test
fun `service unloads model after idle timeout (test override = 1 second)`() = runBlocking {
    // テストでは IDLE_TIMEOUT_MS を override したいが、val なので別の方法：
    //   - Service にテスト用の setter を生やす（@VisibleForTesting）か、
    //   - BuildConfig 経由で値を出し分けるのが現実的。
    // ここは手動検証で確認することにする（自動テスト化は次フェーズ）。
}
```

**実装ノート:** タイムアウトの自動テストは現状の構造では難しい（Service内部の定数）。`@VisibleForTesting` で setter を露出させるか、BuildConfig から `IDLE_TIMEOUT_MS_OVERRIDE` を取れるようにすると後でテスト化しやすい。MVP段階では手動検証（Task 20）で確認する。

- [ ] **Step 3: ビルド確認**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/llm/LlmInferenceService.kt
git commit -m "feat(llm): add 5-minute idle timeout to LlmInferenceService"
```

---

## Task 8: SummarizeWorker を Service経由に切替

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/work/SummarizeWorker.kt`
- Modify: `app/src/androidTest/kotlin/com/example/aiinbox/work/SummarizeWorkerTest.kt`
- Modify: `app/src/androidTest/kotlin/com/example/aiinbox/work/TestSummarizeWorkerFactory.kt`

- [ ] **Step 1: `SummarizeWorker.kt` を編集**

```kotlin
package com.example.aiinbox.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.llm.ContentHintDetector
import com.example.aiinbox.llm.LlmServiceClient
import com.example.aiinbox.llm.ModelManager
import com.example.aiinbox.llm.ModelVariant
import com.example.aiinbox.llm.RamDetector
import com.example.aiinbox.notification.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SummarizeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: InboxRepository,
    private val client: LlmServiceClient,
    private val modelManager: ModelManager,
    private val hintDetector: ContentHintDetector,
    private val notifier: NotificationHelper,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return Result.failure()
        val item = repository.getById(itemId) ?: return Result.failure()

        // モデルが無ければ「準備待ち」状態のまま戻す（DLが完了したら別Workerが回収）
        val variant = modelManager.currentVariant() ?: run {
            return Result.retry()  // モデルDL中などは後で再試行
        }

        repository.markProcessing(itemId)
        return try {
            val hint = hintDetector.detect(item.originalText)
            val r = client.submit(item.originalText, hint, variant)
            r.fold(
                onSuccess = { res ->
                    repository.applySummarizeResult(itemId, res)
                    repository.getById(itemId)?.let { notifier.showCompletion(it) }
                    Result.success()
                },
                onFailure = { t ->
                    if (runAttemptCount < MAX_RETRIES) {
                        Result.retry()
                    } else {
                        repository.markFailed(itemId, t.message ?: t::class.simpleName ?: "unknown")
                        Result.failure()
                    }
                }
            )
        } catch (t: Throwable) {
            if (runAttemptCount < MAX_RETRIES) Result.retry()
            else {
                repository.markFailed(itemId, t.message ?: "unknown")
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_ITEM_ID = "item_id"
        private const val MAX_RETRIES = 1
    }
}
```

- [ ] **Step 2: `TestSummarizeWorkerFactory.kt` を更新**

```kotlin
package com.example.aiinbox.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.llm.ContentHintDetector
import com.example.aiinbox.llm.LlmServiceClient
import com.example.aiinbox.llm.ModelManager
import com.example.aiinbox.notification.NotificationHelper

class TestSummarizeWorkerFactory(
    private val repo: InboxRepository,
    private val client: LlmServiceClient,
    private val modelManager: ModelManager,
    private val hintDetector: ContentHintDetector,
    private val notifier: NotificationHelper,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        SummarizeWorker::class.java.name ->
            SummarizeWorker(appContext, workerParameters, repo, client, modelManager, hintDetector, notifier)
        else -> null
    }
}
```

- [ ] **Step 3: `SummarizeWorkerTest.kt` を更新**

`LlmServiceClient` をHilt注入で使う形にするか、テスト用Fakeを書く。最小例：
```kotlin
// 既存テストが LlmServiceClient を取れるよう setup を書き直す
@Inject lateinit var client: LlmServiceClient
@Inject lateinit var modelManager: ModelManager

@Before
fun setup() {
    hiltRule.inject()
    // ... 既存DBセットアップ
    // モデルが無いケース → Result.retry() を返すことの確認
}
```

詳細はテスト方針が変わるので、AndroidTestをHiltAndroidTest化することを推奨。元のテスト目的（PENDING→COMPLETED）は、Plan 2では`LlmServiceClient`内部でFakeLlmEngineが動くため引き続き成立。

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/work/SummarizeWorker.kt \
        app/src/androidTest/kotlin/com/example/aiinbox/work/
git commit -m "refactor(work): route SummarizeWorker through LlmServiceClient (Service-bound)"
```

---

## Task 9: ModelDownloadWorker（HTTP DL + レジューム）

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/work/ModelDownloadWorker.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/work/ModelDownloadProgress.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/di/NetworkModule.kt`
- Test: `app/src/androidTest/kotlin/com/example/aiinbox/work/ModelDownloadWorkerTest.kt`

- [ ] **Step 1: `NetworkModule.kt` を作成**

```kotlin
package com.example.aiinbox.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // 無制限（DL中は長くなる）
        .retryOnConnectionFailure(true)
        .build()
}
```

- [ ] **Step 2: `ModelDownloadProgress.kt` を作成**

```kotlin
package com.example.aiinbox.work

sealed interface ModelDownloadProgress {
    data object Idle : ModelDownloadProgress
    data class InProgress(val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadProgress
    data class Completed(val variant: com.example.aiinbox.llm.ModelVariant) : ModelDownloadProgress
    data class Failed(val message: String) : ModelDownloadProgress
}
```

- [ ] **Step 3: `ModelDownloadWorker.kt` を作成**

```kotlin
package com.example.aiinbox.work

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.aiinbox.R
import com.example.aiinbox.llm.ModelManager
import com.example.aiinbox.llm.ModelVariant
import com.example.aiinbox.notification.NotificationChannels
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val modelManager: ModelManager,
    private val httpClient: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notif = androidx.core.app.NotificationCompat.Builder(
            applicationContext,
            NotificationChannels.CHANNEL_DOWNLOAD,
        )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.dl_notif_title))
            .setProgress(100, 0, true)
            .setOngoing(true)
            .build()

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    override suspend fun doWork(): Result {
        val variantName = inputData.getString(KEY_VARIANT) ?: return Result.failure()
        val variant = ModelVariant.valueOf(variantName)

        return try {
            setForeground(getForegroundInfo())
            withContext(Dispatchers.IO) {
                downloadWithResume(variant)
            }
            Result.success(Data.Builder().putString(KEY_VARIANT, variant.name).build())
        } catch (t: Throwable) {
            if (runAttemptCount < MAX_RETRIES) Result.retry()
            else Result.failure(Data.Builder().putString(KEY_ERROR, t.message ?: "unknown").build())
        }
    }

    private suspend fun downloadWithResume(variant: ModelVariant) {
        val target: File = modelManager.modelFilePath(variant)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        val existing = if (tmp.exists()) tmp.length() else 0L

        val request = Request.Builder()
            .url(modelManager.downloadUrl(variant))
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: error("empty body")
            val totalKnown = response.header("Content-Length")?.toLongOrNull()?.let { it + existing }
            val total = totalKnown ?: modelManager.expectedSizeBytes(variant)

            RandomAccessFile(tmp, "rw").use { raf ->
                raf.seek(existing)
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = existing
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        downloaded += n
                        if (downloaded % (1024 * 1024) < buf.size) {
                            setProgress(
                                Data.Builder()
                                    .putLong(KEY_DOWNLOADED, downloaded)
                                    .putLong(KEY_TOTAL, total)
                                    .build()
                            )
                        }
                    }
                }
            }
        }

        // tmp → target にrename
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        const val KEY_VARIANT = "variant"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        private const val NOTIF_ID = 0x10D1
        private const val MAX_RETRIES = 3
    }
}
```

- [ ] **Step 4: `NotificationChannels.kt` にDL用チャンネルを追加**

```kotlin
const val CHANNEL_DOWNLOAD = "model_download"

// ensureCreated() に追加
nm.createNotificationChannel(
    NotificationChannel(
        CHANNEL_DOWNLOAD,
        context.getString(R.string.notification_channel_download),
        NotificationManager.IMPORTANCE_LOW,
    )
)
```

- [ ] **Step 5: `strings.xml` に追加**

```xml
<string name="notification_channel_download">モデルダウンロード</string>
<string name="dl_notif_title">モデルをダウンロード中…</string>
```

- [ ] **Step 6: AndroidTest（MockWebServer）**

```kotlin
package com.example.aiinbox.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.aiinbox.llm.ModelManager
import com.example.aiinbox.llm.ModelVariant
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelDownloadWorkerTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private lateinit var modelManager: TestableModelManager
    private val httpClient = OkHttpClient()

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        server.start()
        modelManager = TestableModelManager(ctx, urlOverride = server.url("/model.task").toString())
        modelManager.deleteModel(ModelVariant.GEMMA_4_E2B)
    }

    @After
    fun teardown() { server.shutdown() }

    @Test
    fun `downloads file and writes to model path`() = runBlocking {
        val payload = ByteArray(2048) { (it % 256).toByte() }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Length", payload.size.toString())
                .setBody(Buffer().write(payload))
        )

        val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(ctx)
            .setInputData(Data.Builder().putString(ModelDownloadWorker.KEY_VARIANT, "GEMMA_4_E2B").build())
            .setWorkerFactory(TestModelDownloadWorkerFactory(modelManager, httpClient))
            .build()

        val result = worker.doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(modelManager.isModelPresent(ModelVariant.GEMMA_4_E2B)).isTrue()
        assertThat(modelManager.modelFilePath(ModelVariant.GEMMA_4_E2B).readBytes()).isEqualTo(payload)
    }
}

/** URLをoverrideできるテスト用ModelManager */
class TestableModelManager(ctx: Context, val urlOverride: String) :
    ModelManager(ctx) {
    override fun downloadUrl(variant: ModelVariant): String = urlOverride
}
```

`TestModelDownloadWorkerFactory.kt`:
```kotlin
package com.example.aiinbox.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.aiinbox.llm.ModelManager
import okhttp3.OkHttpClient

class TestModelDownloadWorkerFactory(
    private val modelManager: ModelManager,
    private val http: OkHttpClient,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context, workerClassName: String, workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        ModelDownloadWorker::class.java.name ->
            ModelDownloadWorker(appContext, workerParameters, modelManager, http)
        else -> null
    }
}
```

**Note:** `ModelManager.downloadUrl()` を `open` にする必要があるので、`Task 3` で書いたクラスを `open class ModelManager` に変更。`@Inject` constructor + `open` の併用は問題ない（KSPは個別クラスで動作）。

- [ ] **Step 7: `ModelManager` を `open` に変更**

```kotlin
@Singleton
open class ModelManager @Inject constructor(...)
// downloadUrl も `open fun downloadUrl(...)`
```

- [ ] **Step 8: テスト実行**

```bash
./gradlew :app:connectedDebugAndroidTest --tests com.example.aiinbox.work.ModelDownloadWorkerTest
```
Expected: PASS

- [ ] **Step 9: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/work/ModelDownloadWorker.kt \
        app/src/main/kotlin/com/example/aiinbox/work/ModelDownloadProgress.kt \
        app/src/main/kotlin/com/example/aiinbox/di/NetworkModule.kt \
        app/src/main/kotlin/com/example/aiinbox/llm/ModelManager.kt \
        app/src/main/kotlin/com/example/aiinbox/notification/NotificationChannels.kt \
        app/src/main/res/values/strings.xml \
        app/src/androidTest/kotlin/com/example/aiinbox/work/
git commit -m "feat(work): add ModelDownloadWorker with resume support and progress notification"
```

---

## Task 10: ModelDownloadViewModel + UI Screen

**目的:** モデルがない時に表示する初回ダウンロード画面。RAMから自動でvariantを選択、Wi-Fi未接続時は警告ダイアログ。

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/modeldownload/ModelDownloadUiState.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/modeldownload/ModelDownloadViewModel.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/modeldownload/ModelDownloadScreen.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/navigation/Routes.kt`

- [ ] **Step 1: `ModelDownloadUiState.kt` を作成**

```kotlin
package com.example.aiinbox.ui.modeldownload

import com.example.aiinbox.llm.ModelVariant

data class ModelDownloadUiState(
    val variant: ModelVariant = ModelVariant.GEMMA_4_E2B,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val isDownloading: Boolean = false,
    val isCompleted: Boolean = false,
    val errorMessage: String? = null,
    val showMobileNetworkWarning: Boolean = false,
    val canStart: Boolean = true,
)
```

- [ ] **Step 2: `ModelDownloadViewModel.kt` を作成**

```kotlin
package com.example.aiinbox.ui.modeldownload

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.aiinbox.llm.ModelManager
import com.example.aiinbox.llm.RamDetector
import com.example.aiinbox.work.ModelDownloadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelDownloadViewModel @Inject constructor(
    application: Application,
    private val modelManager: ModelManager,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(
        ModelDownloadUiState(variant = RamDetector.selectVariantForDevice(application))
    )
    val state: StateFlow<ModelDownloadUiState> = _state.asStateFlow()

    fun onStartClicked() {
        if (!isOnUnmeteredNetwork()) {
            _state.value = _state.value.copy(showMobileNetworkWarning = true)
            return
        }
        startDownload()
    }

    fun onProceedAnyway() {
        _state.value = _state.value.copy(showMobileNetworkWarning = false)
        startDownload()
    }

    fun onCancelMobileWarning() {
        _state.value = _state.value.copy(showMobileNetworkWarning = false)
    }

    private fun startDownload() {
        val variant = _state.value.variant
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(Data.Builder().putString(ModelDownloadWorker.KEY_VARIANT, variant.name).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        val wm = WorkManager.getInstance(getApplication())
        wm.enqueueUniqueWork("model_dl_${variant.name}", androidx.work.ExistingWorkPolicy.KEEP, request)

        viewModelScope.launch {
            wm.getWorkInfoByIdFlow(request.id).collect { info ->
                if (info == null) return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        val downloaded = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED, 0)
                        val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, modelManager.expectedSizeBytes(variant))
                        _state.value = _state.value.copy(
                            isDownloading = true,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            errorMessage = null,
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _state.value = _state.value.copy(isDownloading = false, isCompleted = true)
                    }
                    WorkInfo.State.FAILED -> {
                        _state.value = _state.value.copy(
                            isDownloading = false,
                            errorMessage = info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "DL失敗",
                        )
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun isOnUnmeteredNetwork(): Boolean {
        val cm = getApplication<Application>().getSystemService<ConnectivityManager>() ?: return false
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
```

- [ ] **Step 3: `ModelDownloadScreen.kt` を作成**

```kotlin
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
```

- [ ] **Step 4: `Routes.kt` を更新**

```kotlin
object Routes {
    const val INBOX = "inbox"
    const val DETAIL = "detail/{id}"
    const val MODEL_DOWNLOAD = "model_download"
    fun detail(id: String) = "detail/$id"
}
```

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/ui/modeldownload/ \
        app/src/main/kotlin/com/example/aiinbox/ui/navigation/Routes.kt
git commit -m "feat(ui): add ModelDownloadViewModel and Screen with mobile network warning"
```

---

## Task 11: MainActivity でモデル可用性チェック → 適切な初期画面へ

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/MainActivity.kt`

- [ ] **Step 1: 実装**

```kotlin
package com.example.aiinbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.aiinbox.llm.ModelManager
import com.example.aiinbox.notification.NotificationHelper
import com.example.aiinbox.ui.detail.DetailScreen
import com.example.aiinbox.ui.detail.DetailViewModel
import com.example.aiinbox.ui.inbox.InboxScreen
import com.example.aiinbox.ui.modeldownload.ModelDownloadScreen
import com.example.aiinbox.ui.navigation.Routes
import com.example.aiinbox.ui.theme.AiInboxTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var modelManager: ModelManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openItemId = intent.getStringExtra(NotificationHelper.EXTRA_OPEN_ITEM_ID)

        setContent {
            AiInboxTheme {
                val nav = rememberNavController()
                val initialRoute = when {
                    modelManager.currentVariant() == null -> Routes.MODEL_DOWNLOAD
                    openItemId != null -> Routes.detail(openItemId)
                    else -> Routes.INBOX
                }

                NavHost(navController = nav, startDestination = initialRoute) {
                    composable(Routes.MODEL_DOWNLOAD) {
                        ModelDownloadScreen(onCompleted = {
                            nav.navigate(Routes.INBOX) {
                                popUpTo(Routes.MODEL_DOWNLOAD) { inclusive = true }
                            }
                        })
                    }
                    composable(Routes.INBOX) {
                        InboxScreen(onItemClick = { id -> nav.navigate(Routes.detail(id)) })
                    }
                    composable(
                        route = Routes.DETAIL,
                        arguments = listOf(navArgument(DetailViewModel.NAV_ARG_ID) { type = NavType.StringType }),
                    ) {
                        DetailScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: ビルド確認**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/MainActivity.kt
git commit -m "feat(ui): redirect to model download screen when no model is present"
```

---

## Task 12: Hilt LlmModule を MediaPipe にスイッチ

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/di/LlmModule.kt`

- [ ] **Step 1: バインディングを差し替え**

```kotlin
package com.example.aiinbox.di

import com.example.aiinbox.llm.ContentHintDetector
import com.example.aiinbox.llm.LlmEngine
import com.example.aiinbox.llm.LlmResponseParser
import com.example.aiinbox.llm.MediaPipeLlmEngine
import com.example.aiinbox.llm.PromptBuilder
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmBindsModule {
    @Binds
    @Singleton
    abstract fun bindLlmEngine(impl: MediaPipeLlmEngine): LlmEngine
}

@Module
@InstallIn(SingletonComponent::class)
object LlmProvidersModule {
    @Provides @Singleton fun providePromptBuilder(): PromptBuilder = PromptBuilder()
    @Provides @Singleton fun provideContentHintDetector(): ContentHintDetector = ContentHintDetector()
    @Provides @Singleton fun provideLlmResponseParser(): LlmResponseParser =
        LlmResponseParser(ZoneId.systemDefault())
}
```

**※ 既存テストへの影響:** `FakeLlmEngine` は本Bindsから外れる。テストでは `@TestInstallIn(replaces = [LlmBindsModule::class], components = [SingletonComponent::class])` でテスト用モジュールから `FakeLlmEngine` を bind すること。

- [ ] **Step 2: テスト用Hiltモジュール（必要に応じて）**

`app/src/androidTest/kotlin/com/example/aiinbox/di/TestLlmModule.kt`:
```kotlin
package com.example.aiinbox.di

import com.example.aiinbox.llm.FakeLlmEngine
import com.example.aiinbox.llm.LlmEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [LlmBindsModule::class])
abstract class TestLlmModule {
    @Binds @Singleton
    abstract fun bindLlmEngine(impl: FakeLlmEngine): LlmEngine
}
```

これでHiltテスト時はFakeが使われ、本番ではMediaPipeが使われる。

- [ ] **Step 3: ビルド確認**

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```
Expected: 全PASS（既存ユニットテストには影響しない、AndroidTestはHiltテストモジュールでFakeを使う）

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/di/LlmModule.kt \
        app/src/androidTest/kotlin/com/example/aiinbox/di/TestLlmModule.kt
git commit -m "feat(di): switch LlmEngine binding from Fake to MediaPipeLlmEngine"
```

---

## Task 13: 手動エンドツーエンド検証

- [ ] **Step 1: モデルファイルを実機/エミュレータに配置（テスト用）**

検証段階で実DLが面倒な場合は手動配置：
```bash
# 端末上のパスを確認
adb shell run-as com.example.aiinbox.debug ls no_backup/files/models/

# ホスト→端末にPush（実モデル）
adb push gemma-4-e2b-q4km.task /data/local/tmp/
adb shell run-as com.example.aiinbox.debug cp /data/local/tmp/gemma-4-e2b-q4km.task no_backup/files/models/
```

- [ ] **Step 2: アプリ初回起動 → モデルなしでDL画面表示**

- [ ] アプリをアンインストール → インストール → 起動
- [ ] `ModelDownloadScreen` が表示される
- [ ] RAM 8GB+の端末ならvariantが「GEMMA_4_E4B」、6-7GBなら「GEMMA_4_E2B」
- [ ] Wi-Fi未接続 → 「ダウンロードを開始」 → モバイル通信警告ダイアログ
- [ ] Wi-Fi接続後 → DL進捗バー表示 → 完了 → Inbox画面へ

- [ ] **Step 3: Share → 実LLM要約 → 完了通知 → 詳細**

- [ ] Chromeで記事を共有 → AI Inbox選択
- [ ] Toast「保存しました」
- [ ] 数秒〜十数秒後、通知「要約が完了しました」
- [ ] Inboxで実要約が表示される（"[Fake]"プレフィクスがない、本物の文章）
- [ ] 詳細画面で抽出されたタグ・人物・場所が妥当

- [ ] **Step 4: 連続Share（モデル保持確認）**

- [ ] 10秒以内に3件連続でShare
- [ ] 最初のShare後にモデルロード（5-15s待ち）、以降は推論時間のみ（早く完了）
- [ ] LlmInferenceServiceの通知が「要約処理中…」と「モデル待機中」を遷移する

- [ ] **Step 5: アイドル5分でアンロード**

- [ ] Shareして要約完了 → 5分以上待つ
- [ ] 通知が消える（Service が `stopSelf` した）
- [ ] `adb shell dumpsys meminfo com.example.aiinbox.debug` でPSSが大幅減（モデル分が解放）

- [ ] **Step 6: 失敗系**

- [ ] DLを途中でWi-Fiオフ → DL一時停止 → Wi-Fi復帰でレジューム
- [ ] 巨大入力（5万文字）をShare → タイムアウト時にコンテキスト半減で再試行 → 成功 or `FAILED`

- [ ] **Step 7: Plan 2 完了マーカーコミット**

```bash
git commit --allow-empty -m "milestone(plan-2): real LLM via MediaPipe + Gemma 4 complete

- ModelManager で .task ファイルライフサイクル管理
- MediaPipeLlmEngine が LlmInference API でGemma 4を推論
- LlmInferenceService (Foreground Service) がジョブキュー駆動でモデル保持、5分アイドルでアンロード
- SummarizeWorker が LlmServiceClient 経由でService にbind
- ModelDownloadWorker が Range-GET レジューム対応でDL、Foreground通知で進捗表示
- 端末RAMから自動で E2B / E4B 選択、Wi-Fi未接続時警告
- アプリ初回起動で ModelDownloadScreen に誘導
- Hilt の LlmEngine binding を Fake から MediaPipe に切替（テストはTestLlmModuleでFake bindingを保つ）

Next: Plan 3 - 検索/フィルタUI、詳細画面の編集・再要約・削除、設定画面、通知の高度化。
"
```

---

## Plan 2 自己チェック

**1. スペックカバレッジ:**
- ✅ MediaPipe LLM Inference + Gemma 4 (Tasks 1, 4)
- ✅ E4B / E2B 端末RAM自動選択 (Task 2)
- ✅ アイドルタイムアウト付き Foreground Service (Tasks 5, 6, 7)
- ✅ Worker → Service bind (Task 8)
- ✅ ModelDownloadWorker (HTTP, Range-GET, Foreground通知) (Task 9)
- ✅ Wi-Fi未接続警告 (Task 10)
- ✅ 起動時のモデル可用性チェック → DL画面誘導 (Task 11)
- ✅ Hilt差し替え (Task 12)

Plan 2 スコープ外（Plan 3）:
- ⏳ 検索バー・フィルタチップUI
- ⏳ 詳細画面の編集・再要約・削除（Undo）
- ⏳ 設定画面
- ⏳ イベント検出時のアクションボタン付き通知
- ⏳ 通知グルーピング

**2. プレースホルダ走査:** Task 3 に「TODO: DL URL確認」コメントあり。これは外部依存（実URLが2026-05時点で確定していない）なので、実装時に確認・差し替えが必要なポイントとして明示残し。他のTBD/TODOなし。

**3. 型整合性:**
- `LlmEngine` interface のシグネチャは Plan 1 と一致
- `LlmInferenceService.Job` data class は Task 5 → 6 → 8 で一貫
- `LlmServiceClient.submit(text, hint, variant): Result<SummarizeResult>` は Task 8 で使用
- `ModelManager.downloadUrl()` は `open` に変更（Task 9 のテストで override）
- `ModelVariant.FAKE` は本Plan以降は本番DIから外れるが、テスト用 `TestLlmModule` で引き続きbind可能

---

## 実行ハンドオフ

Plan 2完成、 `docs/superpowers/plans/2026-05-02-android-ai-inbox-real-llm.md` に保存。Plan 1 の完了後にこの Plan を実行する。

実行方法は2択：
**1. Subagent-Driven (推奨)** — タスクごとに新規subagent + タスク間レビュー
**2. Inline Execution** — 本セッションで`executing-plans` skillでバッチ実行

選択は Plan 1 / 2 / 3 全部書き終わってからまとめてユーザーに確認する。
