# Android AI Inbox — Plan 1: Foundation 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shareインテントで受信したテキストを暗号化Roomに保存し、`FakeLlmEngine` でフェイク要約を生成、最小限のInbox/Detail UIで表示できる「動くMVP土台」を構築する。

**Architecture:** シングルモジュール (`:app`) Android アプリ。Kotlin + Jetpack Compose + Hilt + Room + SQLCipher + WorkManager。LLM推論は `LlmEngine` インターフェースで抽象化し、本Planではテスト用の `FakeLlmEngine` で固定応答を返す。MediaPipe実装は Plan 2 で追加。

**Tech Stack:** Kotlin 2.1, AGP 8.7+, Jetpack Compose (BOM), Material 3, Hilt 2.51+, Room 2.6+ (KSP), SQLCipher Android, AndroidX Security, WorkManager 2.9+, Coroutines, kotlinx.serialization, JUnit4, Turbine, Robolectric, Truth.

**スペックリンク:** [`docs/superpowers/specs/2026-05-02-android-ai-inbox-design.md`](../specs/2026-05-02-android-ai-inbox-design.md)

---

## このPlanの完成条件（Definition of Done）

このPlanが完了した時、以下が動作する：

1. ✅ アプリをインストールできる（debug build）
2. ✅ 任意のアプリからテキストを「共有」→ AI Inboxを選択 → Toast「保存しました」
3. ✅ 共有したテキストが暗号化されたRoom DBに保存される
4. ✅ バックグラウンドで `FakeLlmEngine` が走り、フェイク要約 + 抽出メタデータが付く
5. ✅ 完了通知が表示され、タップでInbox画面に遷移
6. ✅ Inbox画面でリスト表示
7. ✅ アイテムタップで詳細画面（読み取り専用）が開く
8. ✅ イベント検出済みアイテムに「📅 カレンダーに追加」ボタン → 標準カレンダーアプリのプリフィル画面が開く
9. ✅ アプリ再起動してもデータが復号できる
10. ✅ ユニットテスト・Repository Instrumented Testがすべてパス

**このPlanのスコープ外**（Plan 2/3で対応）:
- 実LLM (MediaPipe + Gemma 4) 推論
- LlmInferenceService（Foreground Service）
- ModelDownloadWorker / モデルDL UI
- 検索バー・フィルタチップ
- 詳細画面の編集・再要約・削除
- 設定画面
- 通知のイベント検出時アクションボタン・グルーピング

---

## ファイル構成（Plan 1で作成・編集するファイル）

```
android-ai-inbox/
├── settings.gradle.kts                                 [新規]
├── build.gradle.kts                                    [新規] (root)
├── gradle.properties                                   [新規]
├── gradle/libs.versions.toml                           [新規]
├── gradle/wrapper/gradle-wrapper.properties            [新規]
├── gradlew, gradlew.bat                                [新規]
├── app/
│   ├── build.gradle.kts                                [新規]
│   ├── proguard-rules.pro                              [新規]
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml                     [新規]
│   │   │   ├── kotlin/com/example/aiinbox/
│   │   │   │   ├── AiInboxApplication.kt               [新規]
│   │   │   │   ├── MainActivity.kt                     [新規]
│   │   │   │   ├── share/ShareReceiverActivity.kt      [新規]
│   │   │   │   ├── data/
│   │   │   │   │   ├── db/
│   │   │   │   │   │   ├── AppDatabase.kt              [新規]
│   │   │   │   │   │   ├── InboxDao.kt                 [新規]
│   │   │   │   │   │   ├── InboxItem.kt                [新規] (Entity)
│   │   │   │   │   │   ├── ExtractedEvent.kt           [新規]
│   │   │   │   │   │   ├── ItemStatus.kt               [新規]
│   │   │   │   │   │   ├── DbTypeConverters.kt         [新規]
│   │   │   │   │   │   └── FtsCallback.kt              [新規]
│   │   │   │   │   ├── crypto/KeystorePassphraseProvider.kt  [新規]
│   │   │   │   │   └── repository/InboxRepository.kt   [新規]
│   │   │   │   ├── llm/
│   │   │   │   │   ├── LlmEngine.kt                    [新規] (interface + types)
│   │   │   │   │   ├── FakeLlmEngine.kt                [新規]
│   │   │   │   │   ├── ContentHintDetector.kt          [新規]
│   │   │   │   │   ├── PromptBuilder.kt                [新規]
│   │   │   │   │   ├── LlmResponseParser.kt            [新規]
│   │   │   │   │   └── TimeConverter.kt                [新規]
│   │   │   │   ├── work/SummarizeWorker.kt             [新規]
│   │   │   │   ├── notification/
│   │   │   │   │   ├── NotificationChannels.kt         [新規]
│   │   │   │   │   └── NotificationHelper.kt           [新規]
│   │   │   │   ├── calendar/CalendarIntentBuilder.kt   [新規]
│   │   │   │   ├── ui/
│   │   │   │   │   ├── theme/{Color,Theme,Type}.kt     [新規]
│   │   │   │   │   ├── navigation/Routes.kt            [新規]
│   │   │   │   │   ├── inbox/{InboxViewModel,InboxScreen}.kt  [新規]
│   │   │   │   │   └── detail/{DetailViewModel,DetailScreen}.kt [新規]
│   │   │   │   └── di/{DatabaseModule,LlmModule,WorkerModule}.kt [新規]
│   │   │   └── res/values/{strings,themes,colors}.xml  [新規]
│   │   ├── test/kotlin/com/example/aiinbox/...         [新規 多数]
│   │   └── androidTest/kotlin/com/example/aiinbox/...  [新規 多数]
└── .gitignore                                          [編集]
```

---

## Task 1: プロジェクト bootstrap

**目的:** Gradleプロジェクトを最小構成で作成し、`./gradlew :app:assembleDebug` が通る状態にする。

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradlew`, `gradlew.bat` (Gradle Wrapperコマンドで生成)
- Create: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/example/aiinbox/AiInboxApplication.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Modify: `.gitignore`

- [ ] **Step 1: Gradle Wrapperを生成（要 gradle インストール、なければ手動配置）**

```bash
# gradle 8.10+ がローカルにあれば
gradle wrapper --gradle-version=8.10 --distribution-type=all
```

なければ既存Androidプロジェクトから `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle-wrapper.properties` をコピー。`gradle-wrapper.properties` は次のステップで上書きする。

- [ ] **Step 2: `gradle/wrapper/gradle-wrapper.properties` を作成**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10-all.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 3: `settings.gradle.kts` を作成**

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AndroidAiInbox"
include(":app")
```

- [ ] **Step 4: `gradle.properties` を作成**

```properties
org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 5: `gradle/libs.versions.toml` を作成**

```toml
[versions]
agp = "8.7.0"
kotlin = "2.1.0"
ksp = "2.1.0-1.0.29"
hilt = "2.52"
hiltExt = "1.2.0"
room = "2.6.1"
sqlcipher = "4.6.1"
securityCrypto = "1.1.0-alpha06"
work = "2.9.1"
coreKtx = "1.13.1"
lifecycle = "2.8.7"
activityCompose = "1.9.3"
composeBom = "2024.10.01"
navigationCompose = "2.8.4"
coroutines = "1.9.0"
serialization = "1.7.3"
junit = "4.13.2"
truth = "1.4.4"
turbine = "1.2.0"
mockk = "1.13.13"
robolectric = "4.16.1"
androidxJunit = "1.2.1"
androidxTestRunner = "1.6.2"
androidxTestRules = "1.6.1"
androidxArchCore = "2.2.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
androidx-room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
androidx-room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
androidx-work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
androidx-work-testing = { module = "androidx.work:work-testing", version.ref = "work" }
androidx-hilt-work = { module = "androidx.hilt:hilt-work", version.ref = "hiltExt" }
androidx-hilt-compiler = { module = "androidx.hilt:hilt-compiler", version.ref = "hiltExt" }
androidx-hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version.ref = "hiltExt" }
androidx-security-crypto = { module = "androidx.security:security-crypto", version.ref = "securityCrypto" }
androidx-arch-core-testing = { module = "androidx.arch.core:core-testing", version.ref = "androidxArchCore" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-android-testing = { module = "com.google.dagger:hilt-android-testing", version.ref = "hilt" }
sqlcipher = { module = "net.zetetic:sqlcipher-android", version.ref = "sqlcipher" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
junit = { module = "junit:junit", version.ref = "junit" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
mockk-android = { module = "io.mockk:mockk-android", version.ref = "mockk" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-junit = { module = "androidx.test.ext:junit", version.ref = "androidxJunit" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
androidx-test-rules = { module = "androidx.test:rules", version.ref = "androidxTestRules" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

- [ ] **Step 6: `build.gradle.kts` (root) を作成**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

- [ ] **Step 7: `app/build.gradle.kts` を作成**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "uk.nordtek.aiinbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.nordtek.aiinbox"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "uk.nordtek.aiinbox.HiltTestRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true; buildConfig = true }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/LICENSE.md",
            "META-INF/LICENSE-notice.md",
        )
    }

    sourceSets {
        getByName("main").kotlin.srcDirs("src/main/kotlin")
        getByName("test").kotlin.srcDirs("src/test/kotlin")
        getByName("androidTest").kotlin.srcDirs("src/androidTest/kotlin")
    }

    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.turbine)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
```

- [ ] **Step 8: `app/proguard-rules.pro` を作成（最低限）**

```proguard
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class uk.nordtek.aiinbox.**$$serializer { *; }
-keepclassmembers class uk.nordtek.aiinbox.** {
    *** Companion;
}
-keepclasseswithmembers class uk.nordtek.aiinbox.** {
    kotlinx.serialization.KSerializer serializer(...);
}
```

- [ ] **Step 9: `app/src/main/AndroidManifest.xml` を作成（最小、Plan 1終盤で intent-filter等を追記）**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>

    <application
        android:name=".AiInboxApplication"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="false"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.AiInbox"
        tools:targetApi="33">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.AiInbox">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>

    </application>
</manifest>
```

- [ ] **Step 10: `app/src/main/res/xml/data_extraction_rules.xml` を作成**

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root"/>
    </cloud-backup>
    <device-transfer>
        <exclude domain="root"/>
    </device-transfer>
</data-extraction-rules>
```

- [ ] **Step 11: `app/src/main/res/values/strings.xml` を作成**

```xml
<resources>
    <string name="app_name">AI Inbox</string>
    <string name="toast_saved">保存しました</string>
    <string name="inbox_empty">まだ何もありません。テキストを共有してみましょう。</string>
    <string name="add_to_calendar">カレンダーに追加</string>
    <string name="notification_summary_complete_title">要約が完了しました</string>
    <string name="notification_channel_summary_complete">要約完了通知</string>
    <string name="notification_channel_event_detected">イベント検出通知</string>
</resources>
```

- [ ] **Step 12: `app/src/main/res/values/themes.xml` を作成（基本Material3テーマ）**

```xml
<resources xmlns:tools="http://schemas.android.com/tools">
    <style name="Theme.AiInbox" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowSplashScreenBackground" tools:targetApi="31">@android:color/white</item>
    </style>
</resources>
```

- [ ] **Step 13: `app/src/main/kotlin/com/example/aiinbox/AiInboxApplication.kt` を作成**

```kotlin
package uk.nordtek.aiinbox

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AiInboxApplication : Application()
```

- [ ] **Step 14: `app/src/main/kotlin/com/example/aiinbox/MainActivity.kt` を作成（プレースホルダ、後のタスクで本実装）**

```kotlin
package uk.nordtek.aiinbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface { Text("AI Inbox - bootstrap OK") }
        }
    }
}
```

- [ ] **Step 15: `.gitignore` を編集（Android用エントリ追加）**

既存内容に以下を追記：
```
# Android
*.iml
.gradle/
local.properties
.idea/
captures/
.externalNativeBuild/
.cxx/
build/
out/

# Keystore (rare)
*.jks
*.keystore

# OS
.DS_Store
Thumbs.db
```

- [ ] **Step 16: ビルド検証**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 17: コミット**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/ gradlew gradlew.bat \
        app/build.gradle.kts app/proguard-rules.pro app/src .gitignore
git commit -m "build: bootstrap Android Gradle project with Compose + Hilt + Room scaffolding"
```

---

## Task 2: ドメインモデル（純Kotlin、Roomアノテーションなし）

**目的:** Room依存に進む前に、純Kotlinのドメイン型を確定させる。テストはJVMで高速に回せる。

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/data/db/ItemStatus.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/data/db/ExtractedEvent.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/data/db/InboxItem.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/llm/SummarizeResult.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/llm/ContentHint.kt`
- Test: `app/src/test/kotlin/com/example/aiinbox/data/db/InboxItemTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

`app/src/test/kotlin/com/example/aiinbox/data/db/InboxItemTest.kt`:
```kotlin
package uk.nordtek.aiinbox.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InboxItemTest {
    @Test
    fun `default item has empty collections and pending status`() {
        val item = InboxItem(
            id = "abc",
            originalText = "hello",
            originalSubject = null,
            sourceApp = "com.example.foo",
            receivedAt = 1000L,
            status = ItemStatus.PENDING,
            updatedAt = 1000L,
        )
        assertThat(item.tags).isEmpty()
        assertThat(item.people).isEmpty()
        assertThat(item.places).isEmpty()
        assertThat(item.urls).isEmpty()
        assertThat(item.userEditedFields).isEmpty()
        assertThat(item.event).isNull()
        assertThat(item.processingAttempts).isEqualTo(0)
        assertThat(item.lastError).isNull()
    }

    @Test
    fun `extracted event has confidence in 0 to 1`() {
        val event = ExtractedEvent(
            title = "ミーティング",
            startMillis = 1700000000000L,
            endMillis = null,
            location = "新宿",
            confidence = 0.8f,
        )
        assertThat(event.confidence).isIn(com.google.common.collect.Range.closed(0f, 1f))
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.data.db.InboxItemTest
```
Expected: COMPILE FAIL（クラス未定義）

- [ ] **Step 3: `ItemStatus.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.data.db

enum class ItemStatus { PENDING, PROCESSING, COMPLETED, FAILED }
```

- [ ] **Step 4: `ExtractedEvent.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.data.db

data class ExtractedEvent(
    val title: String,
    val startMillis: Long?,
    val endMillis: Long?,
    val location: String?,
    val confidence: Float,
)
```

- [ ] **Step 5: `InboxItem.kt` を作成（Roomアノテーションは Task 10 で追加）**

```kotlin
package uk.nordtek.aiinbox.data.db

data class InboxItem(
    val id: String,
    val originalText: String,
    val originalSubject: String?,
    val sourceApp: String?,
    val receivedAt: Long,
    val status: ItemStatus,
    val processingAttempts: Int = 0,
    val lastError: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
    val event: ExtractedEvent? = null,
    val userEditedFields: Set<String> = emptySet(),
    val updatedAt: Long,
)
```

- [ ] **Step 6: `ContentHint.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.llm

enum class ContentHint { WEB_ARTICLE, CHAT_OR_EMAIL, MEMO, UNKNOWN }
```

- [ ] **Step 7: `SummarizeResult.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.llm

import uk.nordtek.aiinbox.data.db.ExtractedEvent

data class SummarizeResult(
    val title: String?,
    val summary: String?,
    val category: String?,
    val tags: List<String>,
    val people: List<String>,
    val places: List<String>,
    val urls: List<String>,
    val event: ExtractedEvent?,
)
```

- [ ] **Step 8: テストが通ることを確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.data.db.InboxItemTest
```
Expected: PASS

- [ ] **Step 9: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/db/ \
        app/src/main/kotlin/com/example/aiinbox/llm/ContentHint.kt \
        app/src/main/kotlin/com/example/aiinbox/llm/SummarizeResult.kt \
        app/src/test/kotlin/com/example/aiinbox/data/db/InboxItemTest.kt
git commit -m "feat: add core domain types (InboxItem, ExtractedEvent, SummarizeResult, ContentHint)"
```

---

## Task 3: TimeConverter (ISO8601 ⇔ unix millis、終日対応)

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/llm/TimeConverter.kt`
- Test: `app/src/test/kotlin/com/example/aiinbox/llm/TimeConverterTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package uk.nordtek.aiinbox.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId

class TimeConverterTest {
    private val tz = ZoneId.of("Asia/Tokyo")

    @Test
    fun `parses ISO8601 datetime with offset`() {
        val r = TimeConverter.parseToMillis("2026-05-10T14:30:00+09:00", tz)!!
        // 2026-05-10T14:30:00+09:00 = 2026-05-10T05:30:00Z
        assertThat(r.millis).isEqualTo(1778391000000L)
        assertThat(r.allDay).isFalse()
    }

    @Test
    fun `parses ISO8601 date only as start of day in given zone`() {
        val r = TimeConverter.parseToMillis("2026-05-10", tz)!!
        // 2026-05-10 00:00 JST = 2026-05-09T15:00:00Z
        assertThat(r.allDay).isTrue()
        assertThat(r.millis).isEqualTo(1778338800000L)
    }

    @Test
    fun `parses ISO8601 datetime without offset using given zone`() {
        val r = TimeConverter.parseToMillis("2026-05-10T14:30", tz)!!
        // 2026-05-10T14:30 JST = 2026-05-10T05:30:00Z
        assertThat(r.allDay).isFalse()
        assertThat(r.millis).isEqualTo(1778391000000L)
    }

    @Test
    fun `null input returns null`() {
        assertThat(TimeConverter.parseToMillis(null, tz)).isNull()
    }

    @Test
    fun `invalid string returns null`() {
        assertThat(TimeConverter.parseToMillis("not a date", tz)).isNull()
    }

    @Test
    fun `formats millis back to ISO8601 with offset`() {
        // 1778391000000 = 2026-05-10T05:30:00Z = 2026-05-10T14:30:00+09:00
        val s = TimeConverter.formatFromMillis(1778391000000L, tz)
        assertThat(s).isEqualTo("2026-05-10T14:30:00+09:00")
    }
}
```

- [ ] **Step 2: テスト失敗確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.llm.TimeConverterTest
```
Expected: COMPILE FAIL

- [ ] **Step 3: 実装**

```kotlin
package uk.nordtek.aiinbox.llm

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object TimeConverter {

    data class Parsed(val millis: Long, val allDay: Boolean)

    fun parseToMillis(iso: String?, zone: ZoneId): Parsed? {
        if (iso.isNullOrBlank()) return null
        return try {
            // 1. オフセット付き ISO8601 (e.g., 2026-05-10T14:30:00+09:00)
            val odt = OffsetDateTime.parse(iso)
            Parsed(odt.toInstant().toEpochMilli(), allDay = false)
        } catch (_: Exception) {
            try {
                // 2. オフセットなし datetime (e.g., 2026-05-10T14:30 / T14:30:00)
                val ldt = LocalDateTime.parse(iso)
                Parsed(ldt.atZone(zone).toInstant().toEpochMilli(), allDay = false)
            } catch (_: Exception) {
                try {
                    // 3. 日付のみ → 終日扱い
                    val ld = LocalDate.parse(iso)
                    Parsed(ld.atStartOfDay(zone).toInstant().toEpochMilli(), allDay = true)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun formatFromMillis(millis: Long, zone: ZoneId): String {
        val zdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone)
        // OffsetDateTimeとして +09:00 形式で出力
        return zdt.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
```

- [ ] **Step 4: テスト通過確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.llm.TimeConverterTest
```
Expected: PASS（全6件）

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/llm/TimeConverter.kt \
        app/src/test/kotlin/com/example/aiinbox/llm/TimeConverterTest.kt
git commit -m "feat(llm): add TimeConverter for ISO8601 <-> unix millis with all-day support"
```

---

## Task 4: ContentHintDetector

**目的:** 入力テキストから `WEB_ARTICLE` / `CHAT_OR_EMAIL` / `MEMO` を簡易ヒューリスティックで判定。

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/llm/ContentHintDetector.kt`
- Test: `app/src/test/kotlin/com/example/aiinbox/llm/ContentHintDetectorTest.kt`

- [ ] **Step 1: テスト**

```kotlin
package uk.nordtek.aiinbox.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContentHintDetectorTest {
    private val det = ContentHintDetector()

    @Test
    fun `url-prefixed text is web article`() {
        val text = "https://example.com/article\n\nThis is the article body..."
        assertThat(det.detect(text)).isEqualTo(ContentHint.WEB_ARTICLE)
    }

    @Test
    fun `chat-style with sender prefix is chat`() {
        val text = "田中: 明日の打ち合わせ何時から?\n佐藤: 14時だよ"
        assertThat(det.detect(text)).isEqualTo(ContentHint.CHAT_OR_EMAIL)
    }

    @Test
    fun `email-style with header is chat or email`() {
        val text = "From: foo@example.com\nTo: bar@example.com\nSubject: meeting\n\nlet's meet at 3pm tomorrow"
        assertThat(det.detect(text)).isEqualTo(ContentHint.CHAT_OR_EMAIL)
    }

    @Test
    fun `plain text is memo`() {
        val text = "今日のランチは美味しかった。明日はカレーにしよう。"
        assertThat(det.detect(text)).isEqualTo(ContentHint.MEMO)
    }

    @Test
    fun `empty text is unknown`() {
        assertThat(det.detect("")).isEqualTo(ContentHint.UNKNOWN)
        assertThat(det.detect("   ")).isEqualTo(ContentHint.UNKNOWN)
    }
}
```

- [ ] **Step 2: テスト失敗確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.llm.ContentHintDetectorTest
```
Expected: COMPILE FAIL

- [ ] **Step 3: 実装**

```kotlin
package uk.nordtek.aiinbox.llm

class ContentHintDetector {
    private val urlRegex = Regex("""\bhttps?://\S+""")
    private val emailHeaderRegex = Regex("""^(From|To|Subject|Cc|Bcc):""", RegexOption.IGNORE_CASE)
    // 行頭の「<名前>: 」または「<名前>:」（チャット風）
    private val chatLineRegex = Regex("""^[\p{L}\p{N}_぀-ヿ一-鿿 .]{1,30}:\s""")

    fun detect(text: String): ContentHint {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ContentHint.UNKNOWN

        val firstLines = trimmed.lineSequence().take(5).toList()

        // メールヘッダ
        if (firstLines.any { emailHeaderRegex.containsMatchIn(it) }) {
            return ContentHint.CHAT_OR_EMAIL
        }

        // チャット風（複数行で発信者プレフィクスが出る）
        val chatLineCount = trimmed.lineSequence().take(20)
            .count { chatLineRegex.containsMatchIn(it) }
        if (chatLineCount >= 2) return ContentHint.CHAT_OR_EMAIL

        // URLが先頭にある or 全体に占めるwordsの比率が高め → 記事
        if (firstLines.firstOrNull()?.let { urlRegex.containsMatchIn(it) } == true) {
            return ContentHint.WEB_ARTICLE
        }
        if (urlRegex.findAll(trimmed).count() >= 1 && trimmed.length > 200) {
            return ContentHint.WEB_ARTICLE
        }

        return ContentHint.MEMO
    }
}
```

- [ ] **Step 4: テスト通過確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.llm.ContentHintDetectorTest
```
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/llm/ContentHintDetector.kt \
        app/src/test/kotlin/com/example/aiinbox/llm/ContentHintDetectorTest.kt
git commit -m "feat(llm): add ContentHintDetector for content type heuristics"
```

---

## Task 5: PromptBuilder

**目的:** `ContentHint` ごとにLLM用プロンプトを生成。本Plan時点では `FakeLlmEngine` で使用、Plan 2でも同じものを再利用。

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/llm/PromptBuilder.kt`
- Test: `app/src/test/kotlin/com/example/aiinbox/llm/PromptBuilderTest.kt`

- [ ] **Step 1: テスト**

```kotlin
package uk.nordtek.aiinbox.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PromptBuilderTest {
    private val pb = PromptBuilder()

    @Test
    fun `prompt contains schema instructions and input text`() {
        val prompt = pb.build("Hello world", ContentHint.MEMO)
        assertThat(prompt).contains("\"title\"")
        assertThat(prompt).contains("\"summary\"")
        assertThat(prompt).contains("\"event\"")
        assertThat(prompt).contains("Hello world")
    }

    @Test
    fun `chat hint adds date interpretation guidance`() {
        val prompt = pb.build("田中: 明日10時集合", ContentHint.CHAT_OR_EMAIL)
        assertThat(prompt).contains("日付")
    }

    @Test
    fun `truncates very long input`() {
        val long = "あ".repeat(20_000)
        val prompt = pb.build(long, ContentHint.WEB_ARTICLE)
        assertThat(prompt.length).isLessThan(15_000)
    }
}
```

- [ ] **Step 2: テスト失敗確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.llm.PromptBuilderTest
```
Expected: COMPILE FAIL

- [ ] **Step 3: 実装**

```kotlin
package uk.nordtek.aiinbox.llm

class PromptBuilder(
    private val maxInputChars: Int = 8000,
) {
    fun build(text: String, hint: ContentHint): String {
        val truncated = if (text.length > maxInputChars) {
            text.substring(0, maxInputChars) + "\n\n[...以下省略...]"
        } else text

        val hintGuidance = when (hint) {
            ContentHint.CHAT_OR_EMAIL -> CHAT_GUIDANCE
            ContentHint.WEB_ARTICLE -> ARTICLE_GUIDANCE
            ContentHint.MEMO -> MEMO_GUIDANCE
            ContentHint.UNKNOWN -> ""
        }

        return SYSTEM_PROMPT
            .replace("{{HINT_GUIDANCE}}", hintGuidance)
            .replace("{{INPUT}}", truncated)
    }

    companion object {
        private const val SYSTEM_PROMPT = """あなたはテキストの要約と構造化情報抽出のアシスタントです。
入力テキストを読んで、以下のJSONスキーマに厳密に従ってJSONのみを出力してください。説明文や前置きは禁止です。

{
  "title": "30文字以内の短いタイトル",
  "summary": "200文字以内の要約",
  "category": "仕事|個人|ニュース|買い物|その他",
  "tags": ["string"],
  "people": ["人物名"],
  "places": ["場所名"],
  "urls": ["URL"],
  "event": {
    "title": "イベント名",
    "start_iso": "ISO8601 (時刻不明なら YYYY-MM-DD のみ)",
    "end_iso": "ISO8601 or null",
    "location": "場所 or null",
    "confidence": 0.0
  }
}

イベントが含まれない場合は "event" を null にしてください。
タイムゾーン未指定の時刻は端末ローカル時刻として解釈してください。

{{HINT_GUIDANCE}}

# 入力テキスト
{{INPUT}}

# 出力（JSONのみ）
"""

        private const val CHAT_GUIDANCE = """この入力はチャットまたはメールです。
日付・時刻表現の解釈に注意してください："明日"、"来週月曜"、"今度の金曜"などの相対表現は、
入力時点の日付（端末の今日）を基準として絶対日時に変換してください。
発信者の名前は people に含めてください。"""

        private const val ARTICLE_GUIDANCE = """この入力はWeb記事です。
記事のメインテーマを title に、本文の論点を summary にまとめてください。
記事内に明示された日時イベント（カンファレンス開催日など）があれば event に抽出してください。"""

        private const val MEMO_GUIDANCE = """この入力は個人のメモまたは議事録です。
要点を summary に、固有名詞があれば people / places に抽出してください。"""
    }
}
```

- [ ] **Step 4: テスト通過確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.llm.PromptBuilderTest
```
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/llm/PromptBuilder.kt \
        app/src/test/kotlin/com/example/aiinbox/llm/PromptBuilderTest.kt
git commit -m "feat(llm): add PromptBuilder with content-type specific guidance"
```

---

## Task 6: LlmResponseParser

**目的:** LLMが返すJSON文字列を `SummarizeResult` にパース。コードフェンスや前置きが混じっても拾える堅牢性。

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/llm/LlmResponseParser.kt`
- Test: `app/src/test/kotlin/com/example/aiinbox/llm/LlmResponseParserTest.kt`
- Test fixtures: `app/src/test/resources/responses/{valid,malformed_json,no_event,with_event_date_only}.json`

- [ ] **Step 1: フィクスチャを作成**

`app/src/test/resources/responses/valid.json`:
```json
{
  "title": "明日のミーティング",
  "summary": "田中さんとの打ち合わせが明日14時から渋谷で予定されている。",
  "category": "仕事",
  "tags": ["打ち合わせ"],
  "people": ["田中"],
  "places": ["渋谷"],
  "urls": [],
  "event": {
    "title": "田中さんと打ち合わせ",
    "start_iso": "2026-05-03T14:00:00+09:00",
    "end_iso": "2026-05-03T15:00:00+09:00",
    "location": "渋谷",
    "confidence": 0.9
  }
}
```

`app/src/test/resources/responses/no_event.json`:
```json
{
  "title": "ランチ感想",
  "summary": "新しいラーメン屋が美味しかった。",
  "category": "個人",
  "tags": ["食事"],
  "people": [],
  "places": [],
  "urls": [],
  "event": null
}
```

`app/src/test/resources/responses/with_event_date_only.json`:
```json
{
  "title": "資格試験",
  "summary": "TOEIC試験は2026年6月15日に開催される。",
  "category": "個人",
  "tags": ["試験"],
  "people": [],
  "places": [],
  "urls": [],
  "event": {
    "title": "TOEIC試験",
    "start_iso": "2026-06-15",
    "end_iso": null,
    "location": null,
    "confidence": 0.85
  }
}
```

`app/src/test/resources/responses/malformed_json.json`:
```
ここはJSONじゃない
```

- [ ] **Step 2: 失敗するテストを書く**

```kotlin
package uk.nordtek.aiinbox.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId

class LlmResponseParserTest {
    private val tz = ZoneId.of("Asia/Tokyo")
    private val parser = LlmResponseParser(tz)

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("responses/$name")!!.readText()

    @Test
    fun `parses valid response with event`() {
        val r = parser.parse(fixture("valid.json"))
        assertThat(r).isNotNull()
        assertThat(r!!.title).isEqualTo("明日のミーティング")
        assertThat(r.people).containsExactly("田中")
        assertThat(r.event).isNotNull()
        assertThat(r.event!!.title).isEqualTo("田中さんと打ち合わせ")
        // 2026-05-03T14:00:00+09:00 = 2026-05-03T05:00:00Z
        assertThat(r.event.startMillis).isEqualTo(1777784400000L)
        // 2026-05-03T15:00:00+09:00 = 2026-05-03T06:00:00Z
        assertThat(r.event.endMillis).isEqualTo(1777788000000L)
    }

    @Test
    fun `parses response without event`() {
        val r = parser.parse(fixture("no_event.json"))!!
        assertThat(r.event).isNull()
    }

    @Test
    fun `parses event with date only`() {
        val r = parser.parse(fixture("with_event_date_only.json"))!!
        assertThat(r.event).isNotNull()
        // 2026-06-15 00:00 JST = 2026-06-14T15:00:00Z
        assertThat(r.event!!.startMillis).isEqualTo(1781449200000L)
        assertThat(r.event.endMillis).isNull()
    }

    @Test
    fun `extracts JSON from inside markdown code fence`() {
        val s = "ここに少し説明\n```json\n" + fixture("no_event.json") + "\n```\n以上"
        val r = parser.parse(s)
        assertThat(r).isNotNull()
        assertThat(r!!.title).isEqualTo("ランチ感想")
    }

    @Test
    fun `returns null for malformed input`() {
        assertThat(parser.parse(fixture("malformed_json.json"))).isNull()
        assertThat(parser.parse("")).isNull()
    }
}
```

- [ ] **Step 3: テスト失敗確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.llm.LlmResponseParserTest
```
Expected: COMPILE FAIL

- [ ] **Step 4: 実装**

```kotlin
package uk.nordtek.aiinbox.llm

import uk.nordtek.aiinbox.data.db.ExtractedEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.ZoneId

class LlmResponseParser(private val zone: ZoneId) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): SummarizeResult? {
        val jsonStr = extractJson(raw) ?: return null
        return try {
            val raw = json.decodeFromString<RawSummarizeResult>(jsonStr)
            val event = raw.event?.let { ev ->
                val start = TimeConverter.parseToMillis(ev.start_iso, zone)
                val end = TimeConverter.parseToMillis(ev.end_iso, zone)
                ExtractedEvent(
                    title = ev.title,
                    startMillis = start?.millis,
                    endMillis = end?.millis,
                    location = ev.location,
                    confidence = ev.confidence,
                )
            }
            SummarizeResult(
                title = raw.title,
                summary = raw.summary,
                category = raw.category,
                tags = raw.tags,
                people = raw.people,
                places = raw.places,
                urls = raw.urls,
                event = event,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** raw 文字列の中からJSONらしき部分を抜き出す（コードフェンスや前置きを除去） */
    private fun extractJson(raw: String): String? {
        if (raw.isBlank()) return null
        // コードフェンス内（```json ... ``` または ``` ... ```）優先
        val fence = Regex("""```(?:json)?\s*(\{[\s\S]*?\})\s*```""").find(raw)
        if (fence != null) return fence.groupValues[1]
        // 最初の `{` から最後の `}` までを試す
        val first = raw.indexOf('{')
        val last = raw.lastIndexOf('}')
        if (first >= 0 && last > first) return raw.substring(first, last + 1)
        return null
    }

    @Serializable
    private data class RawSummarizeResult(
        val title: String? = null,
        val summary: String? = null,
        val category: String? = null,
        val tags: List<String> = emptyList(),
        val people: List<String> = emptyList(),
        val places: List<String> = emptyList(),
        val urls: List<String> = emptyList(),
        val event: RawEvent? = null,
    )

    @Serializable
    private data class RawEvent(
        val title: String,
        val start_iso: String? = null,
        val end_iso: String? = null,
        val location: String? = null,
        val confidence: Float = 0.5f,
    )
}
```

- [ ] **Step 5: テスト通過確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.llm.LlmResponseParserTest
```
Expected: PASS

- [ ] **Step 6: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/llm/LlmResponseParser.kt \
        app/src/test/kotlin/com/example/aiinbox/llm/LlmResponseParserTest.kt \
        app/src/test/resources/responses/
git commit -m "feat(llm): add LlmResponseParser with code-fence and partial JSON tolerance"
```

---

## Task 7: LlmEngine インターフェース + FakeLlmEngine

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/llm/LlmEngine.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/llm/FakeLlmEngine.kt`
- Test: `app/src/test/kotlin/com/example/aiinbox/llm/FakeLlmEngineTest.kt`

- [ ] **Step 1: テスト**

```kotlin
package uk.nordtek.aiinbox.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeLlmEngineTest {
    @Test
    fun `default fake produces non-empty summary and tags`() = runTest {
        val engine = FakeLlmEngine()
        engine.ensureLoaded(ModelVariant.FAKE)
        val r = engine.summarize("これはテストの本文です。", ContentHint.MEMO)
        assertThat(r.summary).isNotEmpty()
        assertThat(r.title).isNotEmpty()
        assertThat(r.category).isNotEmpty()
    }

    @Test
    fun `fake detects fake event when text contains date marker`() = runTest {
        val engine = FakeLlmEngine()
        val r = engine.summarize("__FAKE_EVENT__明日の打ち合わせ", ContentHint.CHAT_OR_EMAIL)
        assertThat(r.event).isNotNull()
        assertThat(r.event!!.title).contains("打ち合わせ")
    }

    @Test
    fun `loaded state toggles correctly`() = runTest {
        val engine = FakeLlmEngine()
        assertThat(engine.isLoaded.value).isFalse()
        engine.ensureLoaded(ModelVariant.FAKE)
        assertThat(engine.isLoaded.value).isTrue()
        engine.unload()
        assertThat(engine.isLoaded.value).isFalse()
    }
}
```

- [ ] **Step 2: テスト失敗確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.llm.FakeLlmEngineTest
```
Expected: COMPILE FAIL

- [ ] **Step 3: `LlmEngine.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.llm

import kotlinx.coroutines.flow.StateFlow

enum class ModelVariant { FAKE, GEMMA_4_E2B, GEMMA_4_E4B }

interface LlmEngine {
    val isLoaded: StateFlow<Boolean>
    suspend fun ensureLoaded(variant: ModelVariant)
    suspend fun unload()
    suspend fun summarize(text: String, hint: ContentHint): SummarizeResult
}
```

- [ ] **Step 4: `FakeLlmEngine.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.llm

import uk.nordtek.aiinbox.data.db.ExtractedEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FakeLlmEngine @Inject constructor() : LlmEngine {
    private val _isLoaded = MutableStateFlow(false)
    override val isLoaded: StateFlow<Boolean> = _isLoaded

    override suspend fun ensureLoaded(variant: ModelVariant) {
        _isLoaded.value = true
    }

    override suspend fun unload() {
        _isLoaded.value = false
    }

    override suspend fun summarize(text: String, hint: ContentHint): SummarizeResult {
        val title = "[Fake] ${text.take(20).replace("\n", " ")}"
        val summary = "[Fake要約] hint=$hint, length=${text.length}"
        val category = when (hint) {
            ContentHint.WEB_ARTICLE -> "ニュース"
            ContentHint.CHAT_OR_EMAIL -> "仕事"
            ContentHint.MEMO -> "個人"
            ContentHint.UNKNOWN -> "その他"
        }
        val event = if (text.contains("__FAKE_EVENT__")) {
            ExtractedEvent(
                title = "打ち合わせ（Fake）",
                startMillis = System.currentTimeMillis() + 24 * 3600_000L,
                endMillis = System.currentTimeMillis() + 25 * 3600_000L,
                location = "Fake Office",
                confidence = 0.9f,
            )
        } else null
        return SummarizeResult(
            title = title,
            summary = summary,
            category = category,
            tags = listOf("fake", hint.name.lowercase()),
            people = emptyList(),
            places = emptyList(),
            urls = emptyList(),
            event = event,
        )
    }
}
```

- [ ] **Step 5: テスト通過確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.llm.FakeLlmEngineTest
```
Expected: PASS

- [ ] **Step 6: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/llm/LlmEngine.kt \
        app/src/main/kotlin/com/example/aiinbox/llm/FakeLlmEngine.kt \
        app/src/test/kotlin/com/example/aiinbox/llm/FakeLlmEngineTest.kt
git commit -m "feat(llm): add LlmEngine interface and FakeLlmEngine for testing"
```

---

## Task 8: CalendarIntentBuilder

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/calendar/CalendarIntentBuilder.kt`
- Test: `app/src/test/kotlin/com/example/aiinbox/calendar/CalendarIntentBuilderTest.kt`

- [ ] **Step 1: テスト（Robolectric）**

```kotlin
package uk.nordtek.aiinbox.calendar

import android.content.Intent
import android.provider.CalendarContract
import uk.nordtek.aiinbox.data.db.ExtractedEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CalendarIntentBuilderTest {

    @Test
    fun `builds insert intent with prefilled extras`() {
        val event = ExtractedEvent(
            title = "ミーティング",
            startMillis = 1700000000000L,
            endMillis = 1700003600000L,
            location = "渋谷",
            confidence = 0.9f,
        )
        val intent = CalendarIntentBuilder.build(
            event = event,
            summary = "明日の打ち合わせ",
            originalTextSnippet = "原文の冒頭",
        )
        assertThat(intent.action).isEqualTo(Intent.ACTION_INSERT)
        assertThat(intent.data).isEqualTo(CalendarContract.Events.CONTENT_URI)
        assertThat(intent.getStringExtra(CalendarContract.Events.TITLE)).isEqualTo("ミーティング")
        assertThat(intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION)).isEqualTo("渋谷")
        assertThat(intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L))
            .isEqualTo(1700000000000L)
        assertThat(intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L))
            .isEqualTo(1700003600000L)
        val desc = intent.getStringExtra(CalendarContract.Events.DESCRIPTION) ?: ""
        assertThat(desc).contains("明日の打ち合わせ")
        assertThat(desc).contains("原文の冒頭")
    }

    @Test
    fun `marks all-day when endMillis is null`() {
        val event = ExtractedEvent(
            title = "終日",
            startMillis = 1700000000000L,
            endMillis = null,
            location = null,
            confidence = 0.7f,
        )
        val intent = CalendarIntentBuilder.build(event, summary = null, originalTextSnippet = null)
        assertThat(intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)).isTrue()
    }
}
```

- [ ] **Step 2: テスト失敗確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.calendar.CalendarIntentBuilderTest
```
Expected: COMPILE FAIL

- [ ] **Step 3: 実装**

```kotlin
package uk.nordtek.aiinbox.calendar

import android.content.Intent
import android.provider.CalendarContract
import uk.nordtek.aiinbox.data.db.ExtractedEvent

object CalendarIntentBuilder {
    fun build(event: ExtractedEvent, summary: String?, originalTextSnippet: String?): Intent {
        val description = buildString {
            if (!summary.isNullOrBlank()) {
                append(summary)
                append("\n\n")
            }
            if (!originalTextSnippet.isNullOrBlank()) {
                append("[原文抜粋]\n")
                append(originalTextSnippet)
            }
        }
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, event.title)
            event.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            event.startMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
            event.endMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            if (event.endMillis == null) {
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            }
            if (description.isNotBlank()) {
                putExtra(CalendarContract.Events.DESCRIPTION, description)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent
    }
}
```

- [ ] **Step 4: テスト通過確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.calendar.CalendarIntentBuilderTest
```
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/calendar/ \
        app/src/test/kotlin/com/example/aiinbox/calendar/
git commit -m "feat(calendar): add CalendarIntentBuilder for ACTION_INSERT prefill"
```

---

## Task 9: Room TypeConverters

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/data/db/DbTypeConverters.kt`
- Test: `app/src/test/kotlin/com/example/aiinbox/data/db/DbTypeConvertersTest.kt`

- [ ] **Step 1: テスト**

```kotlin
package uk.nordtek.aiinbox.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DbTypeConvertersTest {
    private val c = DbTypeConverters()

    @Test
    fun `string list round trip`() {
        val list = listOf("a", "b", "あいうえお")
        assertThat(c.stringListFromJson(c.stringListToJson(list))).isEqualTo(list)
    }

    @Test
    fun `empty string list`() {
        assertThat(c.stringListFromJson(c.stringListToJson(emptyList()))).isEmpty()
    }

    @Test
    fun `null becomes empty for list`() {
        assertThat(c.stringListFromJson(null)).isEmpty()
    }

    @Test
    fun `string set round trip`() {
        val set = setOf("summary", "tags")
        assertThat(c.stringSetFromJson(c.stringSetToJson(set))).isEqualTo(set)
    }

    @Test
    fun `item status round trip`() {
        for (s in ItemStatus.entries) {
            assertThat(c.itemStatusFromString(c.itemStatusToString(s))).isEqualTo(s)
        }
    }
}
```

- [ ] **Step 2: テスト失敗確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.data.db.DbTypeConvertersTest
```
Expected: COMPILE FAIL

- [ ] **Step 3: 実装**

```kotlin
package uk.nordtek.aiinbox.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class DbTypeConverters {
    private val json = Json { encodeDefaults = true }

    @TypeConverter
    fun stringListToJson(list: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), list)

    @TypeConverter
    fun stringListFromJson(s: String?): List<String> =
        if (s.isNullOrBlank()) emptyList()
        else json.decodeFromString(ListSerializer(String.serializer()), s)

    @TypeConverter
    fun stringSetToJson(set: Set<String>): String =
        json.encodeToString(SetSerializer(String.serializer()), set)

    @TypeConverter
    fun stringSetFromJson(s: String?): Set<String> =
        if (s.isNullOrBlank()) emptySet()
        else json.decodeFromString(SetSerializer(String.serializer()), s)

    @TypeConverter
    fun itemStatusToString(s: ItemStatus): String = s.name

    @TypeConverter
    fun itemStatusFromString(s: String): ItemStatus = ItemStatus.valueOf(s)
}
```

- [ ] **Step 4: テスト通過確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.data.db.DbTypeConvertersTest
```
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/db/DbTypeConverters.kt \
        app/src/test/kotlin/com/example/aiinbox/data/db/DbTypeConvertersTest.kt
git commit -m "feat(data): add Room TypeConverters for collections and ItemStatus"
```

---

## Task 10: InboxItem に Room アノテーションを追加

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/InboxItem.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/ExtractedEvent.kt`

- [ ] **Step 1: `ExtractedEvent.kt` を編集**

```kotlin
package uk.nordtek.aiinbox.data.db

import androidx.room.ColumnInfo

data class ExtractedEvent(
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "start_millis") val startMillis: Long?,
    @ColumnInfo(name = "end_millis") val endMillis: Long?,
    @ColumnInfo(name = "location") val location: String?,
    @ColumnInfo(name = "confidence") val confidence: Float,
)
```

- [ ] **Step 2: `InboxItem.kt` を編集**

```kotlin
package uk.nordtek.aiinbox.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "inbox_items",
    indices = [
        Index("received_at"),
        Index("status"),
        Index("category"),
    ],
)
data class InboxItem(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "original_text") val originalText: String,
    @ColumnInfo(name = "original_subject") val originalSubject: String?,
    @ColumnInfo(name = "source_app") val sourceApp: String?,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "status") val status: ItemStatus,
    @ColumnInfo(name = "processing_attempts") val processingAttempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "summary") val summary: String? = null,
    @ColumnInfo(name = "category") val category: String? = null,
    @ColumnInfo(name = "tags") val tags: List<String> = emptyList(),
    @ColumnInfo(name = "people") val people: List<String> = emptyList(),
    @ColumnInfo(name = "places") val places: List<String> = emptyList(),
    @ColumnInfo(name = "urls") val urls: List<String> = emptyList(),
    @Embedded(prefix = "event_") val event: ExtractedEvent? = null,
    @ColumnInfo(name = "user_edited_fields") val userEditedFields: Set<String> = emptySet(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
```

- [ ] **Step 3: 既存ユニットテストが引き続き通ることを確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.data.db.InboxItemTest
```
Expected: PASS

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/db/
git commit -m "feat(data): annotate InboxItem and ExtractedEvent for Room"
```

---

## Task 11: InboxDao（基本クエリ、FTSは別タスク）

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/data/db/InboxDao.kt`

- [ ] **Step 1: 実装**

```kotlin
package uk.nordtek.aiinbox.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: InboxItem)

    @Update
    suspend fun update(item: InboxItem)

    @Delete
    suspend fun delete(item: InboxItem)

    @Query("DELETE FROM inbox_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM inbox_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): InboxItem?

    @Query("SELECT * FROM inbox_items WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<InboxItem?>

    @Query("SELECT * FROM inbox_items ORDER BY received_at DESC")
    fun observeAll(): Flow<List<InboxItem>>

    @Query("SELECT * FROM inbox_items WHERE status = :status ORDER BY received_at ASC")
    suspend fun getByStatus(status: ItemStatus): List<InboxItem>
}
```

- [ ] **Step 2: コミット（コンパイル可能な状態。AppDatabaseはまだ）**

```bash
# まだAppDatabaseがないのでビルドエラーになる場合はTask 12と一緒にコミット可
git add app/src/main/kotlin/com/example/aiinbox/data/db/InboxDao.kt
```

---

## Task 12: AppDatabase（FTS抜き、暗号化抜きの最小構成）

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/data/db/AppDatabase.kt`

- [ ] **Step 1: 実装**

```kotlin
package uk.nordtek.aiinbox.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [InboxItem::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(DbTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun inboxDao(): InboxDao
}
```

- [ ] **Step 2: スキーマエクスポート用の設定を `app/build.gradle.kts` に追加**

`android { ... }` ブロックの末尾に：
```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```
そして `app/schemas/` を `.gitignore` から除外し、トラッキング対象に。

`.gitignore` を編集：
```
# Roomスキーマはコミット対象（マイグレーションテストに必要）
!app/schemas/
```

- [ ] **Step 3: ビルド確認**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL（KSPがDAO実装を生成）

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/db/AppDatabase.kt \
        app/build.gradle.kts \
        .gitignore \
        app/schemas/
git commit -m "feat(data): add AppDatabase with InboxItem entity"
```

---

## Task 13: KeystorePassphraseProvider（SQLCipher用パスフレーズ管理）

**目的:** 初回起動時にランダムパスフレーズを生成、`EncryptedSharedPreferences` で保存。再起動後も同じパスフレーズが取れる。

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/data/crypto/KeystorePassphraseProvider.kt`
- Test: `app/src/test/kotlin/com/example/aiinbox/data/crypto/KeystorePassphraseProviderTest.kt` (Robolectric)

- [ ] **Step 1: テスト**

```kotlin
package uk.nordtek.aiinbox.data.crypto

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeystorePassphraseProviderTest {

    @Test
    fun `first call generates a 32 byte passphrase`() {
        val provider = KeystorePassphraseProvider(ApplicationProvider.getApplicationContext())
        val pass1 = provider.get()
        assertThat(pass1).hasLength(32)
    }

    @Test
    fun `subsequent calls return same passphrase`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pass1 = KeystorePassphraseProvider(ctx).get()
        val pass2 = KeystorePassphraseProvider(ctx).get()
        assertThat(pass2).isEqualTo(pass1)
    }
}
```

- [ ] **Step 2: テスト失敗確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.data.crypto.KeystorePassphraseProviderTest
```
Expected: COMPILE FAIL

- [ ] **Step 3: 実装**

```kotlin
package uk.nordtek.aiinbox.data.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeystorePassphraseProvider @Inject constructor(
    private val context: Context,
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 32文字のランダムパスフレーズを返す（初回生成、以降同じ値）。 */
    fun get(): String {
        val existing = prefs.getString(KEY_PASSPHRASE, null)
        if (existing != null) return existing
        val newPass = generatePassphrase()
        prefs.edit().putString(KEY_PASSPHRASE, newPass).apply()
        return newPass
    }

    private fun generatePassphrase(): String {
        val rnd = SecureRandom()
        val bytes = ByteArray(24)
        rnd.nextBytes(bytes)
        // base64 (URL-safe) → 32文字に切り詰め
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        ).take(32)
    }

    companion object {
        private const val PREFS_FILE = "ai_inbox_keystore"
        private const val KEY_PASSPHRASE = "db_passphrase"
    }
}
```

- [ ] **Step 4: テスト通過確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.data.crypto.KeystorePassphraseProviderTest
```
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/crypto/ \
        app/src/test/kotlin/com/example/aiinbox/data/crypto/
git commit -m "feat(data): add KeystorePassphraseProvider backed by EncryptedSharedPreferences"
```

---

## Task 14: SQLCipher を Room に統合

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/data/db/SqlCipherFactory.kt`
- Test: `app/src/androidTest/kotlin/com/example/aiinbox/data/db/AppDatabaseEncryptionTest.kt`

- [ ] **Step 1: 実装**

```kotlin
package uk.nordtek.aiinbox.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import uk.nordtek.aiinbox.data.crypto.KeystorePassphraseProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object SqlCipherFactory {

    /** 必ずアプリ起動時に1度だけ呼ぶ（SQLCipherのネイティブlib読み込み）。 */
    fun loadLibs(context: Context) {
        net.zetetic.database.sqlcipher.SQLiteDatabase.loadLibs(context)
    }

    fun create(passphraseProvider: KeystorePassphraseProvider): SupportSQLiteOpenHelper.Factory {
        val passphraseBytes = passphraseProvider.get().toByteArray(Charsets.UTF_8)
        return SupportOpenHelperFactory(passphraseBytes)
    }
}

fun buildEncryptedDatabase(
    context: Context,
    passphraseProvider: KeystorePassphraseProvider,
): AppDatabase {
    SqlCipherFactory.loadLibs(context)
    return Room.databaseBuilder(context, AppDatabase::class.java, "inbox.db")
        .openHelperFactory(SqlCipherFactory.create(passphraseProvider))
        .build()
}
```

- [ ] **Step 2: AndroidTest を作成**

`app/src/androidTest/kotlin/com/example/aiinbox/data/db/AppDatabaseEncryptionTest.kt`:
```kotlin
package uk.nordtek.aiinbox.data.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import uk.nordtek.aiinbox.data.crypto.KeystorePassphraseProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseEncryptionTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        ctx.deleteDatabase("inbox.db")
        val provider = KeystorePassphraseProvider(ctx)
        db = buildEncryptedDatabase(ctx, provider)
    }

    @After
    fun teardown() {
        db.close()
        ctx.deleteDatabase("inbox.db")
    }

    @Test
    fun `insert and read item works with encrypted db`() = runBlocking {
        val item = InboxItem(
            id = "id-1",
            originalText = "hello",
            originalSubject = null,
            sourceApp = "test",
            receivedAt = 1000L,
            status = ItemStatus.PENDING,
            updatedAt = 1000L,
        )
        db.inboxDao().insert(item)
        val read = db.inboxDao().getById("id-1")
        assertThat(read?.originalText).isEqualTo("hello")
    }

    @Test
    fun `db file does not contain plaintext`() = runBlocking {
        val item = InboxItem(
            id = "id-2",
            originalText = "PLAINTEXT_MARKER_zzz",
            originalSubject = null,
            sourceApp = "test",
            receivedAt = 1000L,
            status = ItemStatus.PENDING,
            updatedAt = 1000L,
        )
        db.inboxDao().insert(item)
        db.close()

        val dbFile = ctx.getDatabasePath("inbox.db")
        val bytes = dbFile.readBytes()
        val asString = String(bytes, Charsets.ISO_8859_1)
        assertThat(asString).doesNotContain("PLAINTEXT_MARKER_zzz")
    }
}
```

- [ ] **Step 3: HiltTestRunnerを作成（androidTest用）**

`app/src/androidTest/kotlin/com/example/aiinbox/HiltTestRunner.kt`:
```kotlin
package uk.nordtek.aiinbox

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, ctx: Context?): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, ctx)
    }
}
```

- [ ] **Step 4: テスト実行（実機/エミュレータ必要）**

```bash
./gradlew :app:connectedDebugAndroidTest --tests uk.nordtek.aiinbox.data.db.AppDatabaseEncryptionTest
```
Expected: PASS（暗号化されたDBに書けて、ファイルバイト列に平文が現れない）

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/db/SqlCipherFactory.kt \
        app/src/androidTest/kotlin/com/example/aiinbox/
git commit -m "feat(data): wire SQLCipher into Room with passphrase from Keystore"
```

---

## Task 15: FTS5 仮想テーブル + トリガー（Migration callback）

**目的:** Roomの公式FTSは制限が多いので、`RoomDatabase.Callback` で手書きSQLにより `inbox_fts` を作成し、`inbox_items` の変更をトリガーで同期する。

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/data/db/FtsCallback.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/SqlCipherFactory.kt`（callback追加）

- [ ] **Step 1: `FtsCallback.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

object FtsCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        createFtsTable(db)
        createTriggers(db)
    }

    private fun createFtsTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS inbox_fts USING fts5(
                id UNINDEXED,
                title,
                summary,
                original_text,
                tags,
                people,
                places,
                tokenize='unicode61'
            )
            """.trimIndent()
        )
    }

    private fun createTriggers(db: SupportSQLiteDatabase) {
        // INSERT trigger
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS inbox_items_ai AFTER INSERT ON inbox_items BEGIN
                INSERT INTO inbox_fts(id, title, summary, original_text, tags, people, places)
                VALUES (new.id,
                        coalesce(new.title, ''),
                        coalesce(new.summary, ''),
                        new.original_text,
                        coalesce(new.tags, ''),
                        coalesce(new.people, ''),
                        coalesce(new.places, ''));
            END;
            """.trimIndent()
        )
        // DELETE trigger
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS inbox_items_ad AFTER DELETE ON inbox_items BEGIN
                DELETE FROM inbox_fts WHERE id = old.id;
            END;
            """.trimIndent()
        )
        // UPDATE trigger
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS inbox_items_au AFTER UPDATE ON inbox_items BEGIN
                DELETE FROM inbox_fts WHERE id = old.id;
                INSERT INTO inbox_fts(id, title, summary, original_text, tags, people, places)
                VALUES (new.id,
                        coalesce(new.title, ''),
                        coalesce(new.summary, ''),
                        new.original_text,
                        coalesce(new.tags, ''),
                        coalesce(new.people, ''),
                        coalesce(new.places, ''));
            END;
            """.trimIndent()
        )
    }
}
```

- [ ] **Step 2: `SqlCipherFactory.kt` の `buildEncryptedDatabase` にcallbackを追加**

```kotlin
fun buildEncryptedDatabase(
    context: Context,
    passphraseProvider: KeystorePassphraseProvider,
): AppDatabase {
    SqlCipherFactory.loadLibs(context)
    return Room.databaseBuilder(context, AppDatabase::class.java, "inbox.db")
        .openHelperFactory(SqlCipherFactory.create(passphraseProvider))
        .addCallback(FtsCallback)
        .build()
}
```

- [ ] **Step 3: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/db/FtsCallback.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/SqlCipherFactory.kt
git commit -m "feat(data): add FTS5 virtual table with sync triggers via Room callback"
```

---

## Task 16: FTS5 検索クエリを InboxDao に追加

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/InboxDao.kt`
- Test: `app/src/androidTest/kotlin/com/example/aiinbox/data/db/InboxDaoFtsTest.kt`

- [ ] **Step 1: AndroidTestを書く**

```kotlin
package uk.nordtek.aiinbox.data.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import uk.nordtek.aiinbox.data.crypto.KeystorePassphraseProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxDaoFtsTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var dao: InboxDao

    @Before
    fun setup() {
        ctx.deleteDatabase("inbox.db")
        db = buildEncryptedDatabase(ctx, KeystorePassphraseProvider(ctx))
        dao = db.inboxDao()
    }

    @After
    fun teardown() {
        db.close()
        ctx.deleteDatabase("inbox.db")
    }

    @Test
    fun `fts search finds item by summary token`() = runBlocking {
        dao.insert(
            InboxItem(
                id = "1", originalText = "本文1",
                originalSubject = null, sourceApp = null, receivedAt = 1L,
                status = ItemStatus.COMPLETED,
                title = "出張", summary = "東京から大阪へ",
                tags = listOf("仕事"), people = listOf("田中"), places = listOf("大阪"),
                updatedAt = 1L,
            )
        )
        dao.insert(
            InboxItem(
                id = "2", originalText = "本文2",
                originalSubject = null, sourceApp = null, receivedAt = 2L,
                status = ItemStatus.COMPLETED,
                title = "ランチ", summary = "新しいラーメン屋",
                tags = listOf("食事"), people = emptyList(), places = listOf("渋谷"),
                updatedAt = 2L,
            )
        )

        val r = dao.searchFts("大阪")
        assertThat(r.map { it.id }).containsExactly("1")
    }

    @Test
    fun `fts search hits across multiple columns`() = runBlocking {
        dao.insert(
            InboxItem(
                id = "x", originalText = "本文",
                originalSubject = null, sourceApp = null, receivedAt = 1L,
                status = ItemStatus.COMPLETED,
                title = null, summary = null,
                tags = listOf("会議"), people = listOf("山田"), places = emptyList(),
                updatedAt = 1L,
            )
        )
        assertThat(dao.searchFts("会議").map { it.id }).containsExactly("x")
        assertThat(dao.searchFts("山田").map { it.id }).containsExactly("x")
    }
}
```

- [ ] **Step 2: `InboxDao.kt` に検索クエリを追加**

```kotlin
@Query(
    """
    SELECT i.* FROM inbox_items i
    JOIN inbox_fts f ON f.id = i.id
    WHERE inbox_fts MATCH :query
    ORDER BY i.received_at DESC
    """
)
suspend fun searchFts(query: String): List<InboxItem>
```

- [ ] **Step 3: テスト実行**

```bash
./gradlew :app:connectedDebugAndroidTest --tests uk.nordtek.aiinbox.data.db.InboxDaoFtsTest
```
Expected: PASS

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/db/InboxDao.kt \
        app/src/androidTest/kotlin/com/example/aiinbox/data/db/InboxDaoFtsTest.kt
git commit -m "feat(data): add FTS5 search query to InboxDao"
```

---

## Task 17: InboxRepository

**目的:** UIとWorkerが触る公開API。Flow返却、CRUD、編集トラッキング、検索。

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/data/repository/InboxRepository.kt`
- Test: `app/src/androidTest/kotlin/com/example/aiinbox/data/repository/InboxRepositoryTest.kt`

- [ ] **Step 1: テスト**

```kotlin
package uk.nordtek.aiinbox.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import uk.nordtek.aiinbox.data.crypto.KeystorePassphraseProvider
import uk.nordtek.aiinbox.data.db.AppDatabase
import uk.nordtek.aiinbox.data.db.ItemStatus
import uk.nordtek.aiinbox.data.db.buildEncryptedDatabase
import uk.nordtek.aiinbox.llm.SummarizeResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxRepositoryTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: InboxRepository

    @Before
    fun setup() {
        ctx.deleteDatabase("inbox.db")
        db = buildEncryptedDatabase(ctx, KeystorePassphraseProvider(ctx))
        repo = InboxRepository(db.inboxDao())
    }

    @After
    fun teardown() { db.close(); ctx.deleteDatabase("inbox.db") }

    @Test
    fun `createPendingItem returns id and stores PENDING`() = runBlocking {
        val id = repo.createPendingItem("hello", subject = null, sourceApp = "x")
        val read = repo.getById(id)
        assertThat(read?.status).isEqualTo(ItemStatus.PENDING)
        assertThat(read?.originalText).isEqualTo("hello")
    }

    @Test
    fun `applySummarizeResult preserves user-edited fields`() = runBlocking {
        val id = repo.createPendingItem("text", null, null)
        repo.updateField(id, "summary", "ユーザー手動要約")
        val newResult = SummarizeResult(
            title = "AI title", summary = "AI要約", category = "個人",
            tags = listOf("a"), people = emptyList(), places = emptyList(),
            urls = emptyList(), event = null,
        )
        repo.applySummarizeResult(id, newResult)
        val item = repo.getById(id)!!
        assertThat(item.summary).isEqualTo("ユーザー手動要約") // 保護される
        assertThat(item.title).isEqualTo("AI title")          // 上書きされる
    }

    @Test
    fun `observeAll emits updates`() = runBlocking {
        repo.observeAll().test {
            assertThat(awaitItem()).isEmpty()
            repo.createPendingItem("a", null, null)
            assertThat(awaitItem()).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markFailed sets status and increments attempts`() = runBlocking {
        val id = repo.createPendingItem("x", null, null)
        repo.markFailed(id, "OOM")
        val item = repo.getById(id)!!
        assertThat(item.status).isEqualTo(ItemStatus.FAILED)
        assertThat(item.processingAttempts).isEqualTo(1)
        assertThat(item.lastError).isEqualTo("OOM")
    }
}
```

- [ ] **Step 2: テスト失敗確認**

```bash
./gradlew :app:connectedDebugAndroidTest --tests uk.nordtek.aiinbox.data.repository.InboxRepositoryTest
```
Expected: COMPILE FAIL

- [ ] **Step 3: 実装**

```kotlin
package uk.nordtek.aiinbox.data.repository

import uk.nordtek.aiinbox.data.db.InboxDao
import uk.nordtek.aiinbox.data.db.InboxItem
import uk.nordtek.aiinbox.data.db.ItemStatus
import uk.nordtek.aiinbox.llm.SummarizeResult
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InboxRepository @Inject constructor(
    private val dao: InboxDao,
) {

    fun observeAll(): Flow<List<InboxItem>> = dao.observeAll()

    fun observeById(id: String): Flow<InboxItem?> = dao.observeById(id)

    suspend fun getById(id: String): InboxItem? = dao.getById(id)

    suspend fun getPendingItems(): List<InboxItem> = dao.getByStatus(ItemStatus.PENDING)

    suspend fun createPendingItem(text: String, subject: String?, sourceApp: String?): String {
        val now = System.currentTimeMillis()
        val item = InboxItem(
            id = UUID.randomUUID().toString(),
            originalText = text,
            originalSubject = subject,
            sourceApp = sourceApp,
            receivedAt = now,
            status = ItemStatus.PENDING,
            updatedAt = now,
        )
        dao.insert(item)
        return item.id
    }

    suspend fun markProcessing(id: String) {
        val current = dao.getById(id) ?: return
        dao.update(current.copy(status = ItemStatus.PROCESSING, updatedAt = System.currentTimeMillis()))
    }

    suspend fun applySummarizeResult(id: String, result: SummarizeResult) {
        val current = dao.getById(id) ?: return
        val edited = current.userEditedFields

        dao.update(
            current.copy(
                title = if ("title" in edited) current.title else result.title,
                summary = if ("summary" in edited) current.summary else result.summary,
                category = if ("category" in edited) current.category else result.category,
                tags = if ("tags" in edited) current.tags else result.tags,
                people = if ("people" in edited) current.people else result.people,
                places = if ("places" in edited) current.places else result.places,
                urls = if ("urls" in edited) current.urls else result.urls,
                event = if ("event" in edited) current.event else result.event,
                status = ItemStatus.COMPLETED,
                lastError = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun markFailed(id: String, error: String) {
        val current = dao.getById(id) ?: return
        dao.update(
            current.copy(
                status = ItemStatus.FAILED,
                processingAttempts = current.processingAttempts + 1,
                lastError = error,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun incrementAttempts(id: String) {
        val current = dao.getById(id) ?: return
        dao.update(current.copy(processingAttempts = current.processingAttempts + 1))
    }

    suspend fun updateField(id: String, field: String, value: String?) {
        val current = dao.getById(id) ?: return
        val updated = when (field) {
            "title" -> current.copy(title = value)
            "summary" -> current.copy(summary = value)
            "category" -> current.copy(category = value)
            else -> error("updateField: unsupported field $field")
        }
        dao.update(
            updated.copy(
                userEditedFields = updated.userEditedFields + field,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    suspend fun search(query: String): List<InboxItem> {
        if (query.isBlank()) return emptyList()
        // FTS5 MATCH のためにユーザー入力を簡易サニタイズ
        val sanitized = query.replace("\"", "").let { "\"$it\"" }
        return dao.searchFts(sanitized)
    }
}
```

- [ ] **Step 4: テスト通過確認**

```bash
./gradlew :app:connectedDebugAndroidTest --tests uk.nordtek.aiinbox.data.repository.InboxRepositoryTest
```
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/repository/ \
        app/src/androidTest/kotlin/com/example/aiinbox/data/repository/
git commit -m "feat(data): add InboxRepository with CRUD, search, and edit-preserving result apply"
```

---

## Task 18: Hilt モジュール（Database、LLM）

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/di/DatabaseModule.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/di/LlmModule.kt`

- [ ] **Step 1: `DatabaseModule.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.di

import android.content.Context
import uk.nordtek.aiinbox.data.crypto.KeystorePassphraseProvider
import uk.nordtek.aiinbox.data.db.AppDatabase
import uk.nordtek.aiinbox.data.db.InboxDao
import uk.nordtek.aiinbox.data.db.buildEncryptedDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providePassphraseProvider(
        @ApplicationContext ctx: Context,
    ): KeystorePassphraseProvider = KeystorePassphraseProvider(ctx)

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext ctx: Context,
        passphraseProvider: KeystorePassphraseProvider,
    ): AppDatabase = buildEncryptedDatabase(ctx, passphraseProvider)

    @Provides
    fun provideInboxDao(db: AppDatabase): InboxDao = db.inboxDao()
}
```

- [ ] **Step 2: `LlmModule.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.di

import uk.nordtek.aiinbox.llm.ContentHintDetector
import uk.nordtek.aiinbox.llm.FakeLlmEngine
import uk.nordtek.aiinbox.llm.LlmEngine
import uk.nordtek.aiinbox.llm.LlmResponseParser
import uk.nordtek.aiinbox.llm.PromptBuilder
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
    /** Plan 1: LlmEngine の本番束ね先は FakeLlmEngine。Plan 2 で MediaPipeLlmEngine に切替。 */
    @Binds
    @Singleton
    abstract fun bindLlmEngine(impl: FakeLlmEngine): LlmEngine
}

@Module
@InstallIn(SingletonComponent::class)
object LlmProvidersModule {

    @Provides
    @Singleton
    fun providePromptBuilder(): PromptBuilder = PromptBuilder()

    @Provides
    @Singleton
    fun provideContentHintDetector(): ContentHintDetector = ContentHintDetector()

    @Provides
    @Singleton
    fun provideLlmResponseParser(): LlmResponseParser = LlmResponseParser(ZoneId.systemDefault())
}
```

- [ ] **Step 3: ビルド確認**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/di/
git commit -m "feat(di): add DatabaseModule and LlmModule (Fake binding for now)"
```

---

## Task 19: 通知（基本完了通知のみ）

**目的:** チャンネル登録 + 単一アイテム完了用の通知ヘルパ。イベント検出時のアクションボタン・グルーピングは Plan 3 で追加。

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/notification/NotificationChannels.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/notification/NotificationHelper.kt`

- [ ] **Step 1: `NotificationChannels.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

object NotificationChannels {
    const val CHANNEL_SUMMARY_COMPLETE = "summary_complete"
    const val CHANNEL_EVENT_DETECTED = "event_detected"

    fun ensureCreated(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SUMMARY_COMPLETE,
                context.getString(uk.nordtek.aiinbox.R.string.notification_channel_summary_complete),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENT_DETECTED,
                context.getString(uk.nordtek.aiinbox.R.string.notification_channel_event_detected),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }
}
```

- [ ] **Step 2: `NotificationHelper.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import uk.nordtek.aiinbox.MainActivity
import uk.nordtek.aiinbox.R
import uk.nordtek.aiinbox.data.db.InboxItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun showCompletion(item: InboxItem) {
        NotificationChannels.ensureCreated(context)
        val title = item.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.notification_summary_complete_title)
        val text = item.summary ?: ""

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_OPEN_ITEM_ID, item.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_SUMMARY_COMPLETE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        runCatching {
            NotificationManagerCompat.from(context).notify(item.id.hashCode(), builder.build())
        }
    }

    companion object {
        const val EXTRA_OPEN_ITEM_ID = "open_item_id"
    }
}
```

- [ ] **Step 3: ビルド確認**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/notification/
git commit -m "feat(notification): add channels setup and completion notification helper"
```

---

## Task 20: WorkManager + Hilt統合（HiltWorkerFactory）

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/AiInboxApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/example/aiinbox/di/WorkManagerInitializer.kt`

- [ ] **Step 1: `AiInboxApplication.kt` を編集**

```kotlin
package uk.nordtek.aiinbox

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AiInboxApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

- [ ] **Step 2: `AndroidManifest.xml` で WorkManager のデフォルトInitializerを無効化**

`<application>` 直下に追加：
```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

- [ ] **Step 3: ビルド確認**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/AiInboxApplication.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat(work): wire HiltWorkerFactory into Application config"
```

---

## Task 21: SummarizeWorker（FakeLlmEngine使用）

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/work/SummarizeWorker.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/work/WorkScheduler.kt`
- Test: `app/src/androidTest/kotlin/com/example/aiinbox/work/SummarizeWorkerTest.kt`

- [ ] **Step 1: `SummarizeWorker.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import uk.nordtek.aiinbox.data.repository.InboxRepository
import uk.nordtek.aiinbox.llm.ContentHintDetector
import uk.nordtek.aiinbox.llm.LlmEngine
import uk.nordtek.aiinbox.llm.ModelVariant
import uk.nordtek.aiinbox.notification.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SummarizeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: InboxRepository,
    private val llmEngine: LlmEngine,
    private val hintDetector: ContentHintDetector,
    private val notifier: NotificationHelper,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return Result.failure()
        val item = repository.getById(itemId) ?: return Result.failure()

        repository.markProcessing(itemId)
        return try {
            llmEngine.ensureLoaded(ModelVariant.FAKE)
            val hint = hintDetector.detect(item.originalText)
            val result = llmEngine.summarize(item.originalText, hint)
            repository.applySummarizeResult(itemId, result)
            val updated = repository.getById(itemId)
            if (updated != null) notifier.showCompletion(updated)
            Result.success()
        } catch (t: Throwable) {
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                repository.markFailed(itemId, t.message ?: t::class.simpleName ?: "unknown")
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

- [ ] **Step 2: `WorkScheduler.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueueSummarize(itemId: String) {
        val request = OneTimeWorkRequestBuilder<SummarizeWorker>()
            .setInputData(Data.Builder().putString(SummarizeWorker.KEY_ITEM_ID, itemId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("summarize:$itemId", ExistingWorkPolicy.REPLACE, request)
    }
}
```

- [ ] **Step 3: AndroidTestを書く**

```kotlin
package uk.nordtek.aiinbox.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import uk.nordtek.aiinbox.data.crypto.KeystorePassphraseProvider
import uk.nordtek.aiinbox.data.db.AppDatabase
import uk.nordtek.aiinbox.data.db.ItemStatus
import uk.nordtek.aiinbox.data.db.buildEncryptedDatabase
import uk.nordtek.aiinbox.data.repository.InboxRepository
import uk.nordtek.aiinbox.llm.ContentHintDetector
import uk.nordtek.aiinbox.llm.FakeLlmEngine
import uk.nordtek.aiinbox.notification.NotificationHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SummarizeWorkerTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: InboxRepository

    @Before
    fun setup() {
        ctx.deleteDatabase("inbox.db")
        db = buildEncryptedDatabase(ctx, KeystorePassphraseProvider(ctx))
        repo = InboxRepository(db.inboxDao())

        WorkManagerTestInitHelper.initializeTestWorkManager(
            ctx,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .build()
        )
    }

    @After fun teardown() { db.close(); ctx.deleteDatabase("inbox.db") }

    @Test
    fun `worker transitions PENDING to COMPLETED with fake engine`() = runBlocking {
        val id = repo.createPendingItem("テストの本文", null, "test")

        val worker = TestListenableWorkerBuilder<SummarizeWorker>(ctx)
            .setInputData(Data.Builder().putString(SummarizeWorker.KEY_ITEM_ID, id).build())
            .setWorkerFactory(
                TestSummarizeWorkerFactory(repo, FakeLlmEngine(), ContentHintDetector(), NotificationHelper(ctx))
            )
            .build()

        val result = worker.doWork()
        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        val item = repo.getById(id)!!
        assertThat(item.status).isEqualTo(ItemStatus.COMPLETED)
        assertThat(item.summary).isNotEmpty()
    }
}
```

- [ ] **Step 4: テスト用Worker Factoryを書く（同じファイル内 or 別ファイル）**

`app/src/androidTest/kotlin/com/example/aiinbox/work/TestSummarizeWorkerFactory.kt`:
```kotlin
package uk.nordtek.aiinbox.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import uk.nordtek.aiinbox.data.repository.InboxRepository
import uk.nordtek.aiinbox.llm.ContentHintDetector
import uk.nordtek.aiinbox.llm.LlmEngine
import uk.nordtek.aiinbox.notification.NotificationHelper

class TestSummarizeWorkerFactory(
    private val repo: InboxRepository,
    private val engine: LlmEngine,
    private val hintDetector: ContentHintDetector,
    private val notifier: NotificationHelper,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        return when (workerClassName) {
            SummarizeWorker::class.java.name ->
                SummarizeWorker(appContext, workerParameters, repo, engine, hintDetector, notifier)
            else -> null
        }
    }
}
```

- [ ] **Step 5: テスト実行**

```bash
./gradlew :app:connectedDebugAndroidTest --tests uk.nordtek.aiinbox.work.SummarizeWorkerTest
```
Expected: PASS

- [ ] **Step 6: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/work/ \
        app/src/androidTest/kotlin/com/example/aiinbox/work/
git commit -m "feat(work): add SummarizeWorker with FakeLlmEngine and WorkScheduler"
```

---

## Task 22: ShareReceiverActivity

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/share/ShareReceiverActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: `ShareReceiverActivity.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import uk.nordtek.aiinbox.R
import uk.nordtek.aiinbox.data.repository.InboxRepository
import uk.nordtek.aiinbox.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject lateinit var repository: InboxRepository
    @Inject lateinit var workScheduler: WorkScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val sourceApp = referrer?.host

        if (text.isNullOrBlank()) {
            Toast.makeText(this, "テキストが見つかりません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val id = repository.createPendingItem(text, subject, sourceApp)
            workScheduler.enqueueSummarize(id)
            finish()
        }
    }
}
```

- [ ] **Step 2: `AndroidManifest.xml` に Activity と intent-filter を追加**

`<application>` 内に：
```xml
<activity
    android:name=".share.ShareReceiverActivity"
    android:exported="true"
    android:theme="@android:style/Theme.NoDisplay"
    android:taskAffinity=""
    android:excludeFromRecents="true">
    <intent-filter>
        <action android:name="android.intent.action.SEND"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <data android:mimeType="text/plain"/>
    </intent-filter>
</activity>
```

- [ ] **Step 3: ビルド確認**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/share/ \
        app/src/main/AndroidManifest.xml
git commit -m "feat(share): add ShareReceiverActivity with text/plain intent filter"
```

---

## Task 23: Material 3 テーマ

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/theme/Color.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/theme/Type.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/theme/Theme.kt`

- [ ] **Step 1: `Color.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.ui.theme

import androidx.compose.ui.graphics.Color

val Purple40 = Color(0xFF6650A4)
val PurpleGrey40 = Color(0xFF625B71)
val Pink40 = Color(0xFF7D5260)

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
```

- [ ] **Step 2: `Type.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.ui.theme

import androidx.compose.material3.Typography

val Typography = Typography()
```

- [ ] **Step 3: `Theme.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80,
)

private val LightColors = lightColorScheme(
    primary = Purple40, secondary = PurpleGrey40, tertiary = Pink40,
)

@Composable
fun AiInboxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
```

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/ui/theme/
git commit -m "feat(ui): add Material 3 theme scaffolding"
```

---

## Task 24: InboxViewModel + 状態定義

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxUiState.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxViewModel.kt`
- Test: `app/src/test/kotlin/com/example/aiinbox/ui/inbox/InboxViewModelTest.kt`

- [ ] **Step 1: `InboxUiState.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.ui.inbox

import uk.nordtek.aiinbox.data.db.InboxItem

data class InboxUiState(
    val items: List<InboxItem> = emptyList(),
    val loading: Boolean = true,
)
```

- [ ] **Step 2: テスト**

```kotlin
package uk.nordtek.aiinbox.ui.inbox

import app.cash.turbine.test
import uk.nordtek.aiinbox.data.db.InboxItem
import uk.nordtek.aiinbox.data.db.ItemStatus
import uk.nordtek.aiinbox.data.repository.InboxRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test
    fun `state reflects repository emissions`() = runTest {
        val flow = MutableStateFlow<List<InboxItem>>(emptyList())
        val repo: InboxRepository = mockk()
        every { repo.observeAll() } returns flow

        val vm = InboxViewModel(repo)
        vm.uiState.test {
            assertThat(awaitItem().items).isEmpty()
            flow.value = listOf(sampleItem("1"))
            assertThat(awaitItem().items).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun sampleItem(id: String) = InboxItem(
        id = id, originalText = "x",
        originalSubject = null, sourceApp = null,
        receivedAt = 1L, status = ItemStatus.COMPLETED, updatedAt = 1L,
    )
}
```

- [ ] **Step 3: 実装**

`InboxViewModel.kt`:
```kotlin
package uk.nordtek.aiinbox.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import uk.nordtek.aiinbox.data.repository.InboxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class InboxViewModel @Inject constructor(
    private val repository: InboxRepository,
) : ViewModel() {

    val uiState: StateFlow<InboxUiState> = repository.observeAll()
        .map { items -> InboxUiState(items = items, loading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = InboxUiState(loading = true),
        )
}
```

- [ ] **Step 4: テスト通過確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.ui.inbox.InboxViewModelTest
```
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/ui/inbox/ \
        app/src/test/kotlin/com/example/aiinbox/ui/inbox/
git commit -m "feat(ui): add InboxViewModel observing repository all-items flow"
```

---

## Task 25: InboxScreen（基本リスト）

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxScreen.kt`

- [ ] **Step 1: 実装**

```kotlin
package uk.nordtek.aiinbox.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.nordtek.aiinbox.R
import uk.nordtek.aiinbox.data.db.InboxItem
import uk.nordtek.aiinbox.data.db.ItemStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onItemClick: (String) -> Unit,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("AI Inbox") }) },
    ) { padding ->
        if (state.items.isEmpty() && !state.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResourceCompat(R.string.inbox_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    InboxItemCard(item = item, onClick = { onItemClick(item.id) })
                }
            }
        }
    }
}

@Composable
private fun InboxItemCard(item: InboxItem, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable { onClick() }) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = item.title ?: item.originalText.take(40),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.summary ?: "(処理待ち...)",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                if (item.status == ItemStatus.PENDING || item.status == ItemStatus.PROCESSING) {
                    StatusChip("処理中")
                }
                if (item.status == ItemStatus.FAILED) StatusChip("失敗")
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

// Lifecycle変更で TooltipBox 等の API差異を吸収する小ヘルパ。Resource取得を 1 行で。
@Composable
private fun stringResourceCompat(id: Int): String =
    androidx.compose.ui.res.stringResource(id)

// import補完のためのRow（一覧での横並び表示用）
@Composable
private fun Row(
    horizontalArrangement: Arrangement.Horizontal,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        content = { content() },
    )
}
```

- [ ] **Step 2: ビルド確認**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/ui/inbox/InboxScreen.kt
git commit -m "feat(ui): add basic InboxScreen with list of items and status chips"
```

---

## Task 26: DetailViewModel

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/detail/DetailUiState.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/detail/DetailViewModel.kt`
- Test: `app/src/test/kotlin/com/example/aiinbox/ui/detail/DetailViewModelTest.kt`

- [ ] **Step 1: `DetailUiState.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.ui.detail

import uk.nordtek.aiinbox.data.db.InboxItem

data class DetailUiState(
    val item: InboxItem? = null,
    val loading: Boolean = true,
)
```

- [ ] **Step 2: テスト**

```kotlin
package uk.nordtek.aiinbox.ui.detail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import uk.nordtek.aiinbox.data.db.InboxItem
import uk.nordtek.aiinbox.data.db.ItemStatus
import uk.nordtek.aiinbox.data.repository.InboxRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test
    fun `loads item by id from save state`() = runTest {
        val flow = MutableStateFlow<InboxItem?>(null)
        val repo: InboxRepository = mockk()
        every { repo.observeById("abc") } returns flow

        val vm = DetailViewModel(repo, SavedStateHandle(mapOf(DetailViewModel.NAV_ARG_ID to "abc")))
        vm.uiState.test {
            assertThat(awaitItem().item).isNull()
            flow.value = InboxItem(
                id = "abc", originalText = "x",
                originalSubject = null, sourceApp = null,
                receivedAt = 1L, status = ItemStatus.COMPLETED, updatedAt = 1L,
            )
            assertThat(awaitItem().item?.id).isEqualTo("abc")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 3: 実装**

```kotlin
package uk.nordtek.aiinbox.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import uk.nordtek.aiinbox.data.repository.InboxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: InboxRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val itemId: String = checkNotNull(savedStateHandle[NAV_ARG_ID])

    val uiState: StateFlow<DetailUiState> = repository.observeById(itemId)
        .map { item -> DetailUiState(item = item, loading = item == null) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DetailUiState(loading = true),
        )

    companion object {
        const val NAV_ARG_ID = "id"
    }
}
```

- [ ] **Step 4: テスト通過確認**

```bash
./gradlew :app:testDebugUnitTest --tests uk.nordtek.aiinbox.ui.detail.DetailViewModelTest
```
Expected: PASS

- [ ] **Step 5: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/ui/detail/ \
        app/src/test/kotlin/com/example/aiinbox/ui/detail/
git commit -m "feat(ui): add DetailViewModel observing single inbox item"
```

---

## Task 27: DetailScreen（読み取り専用、カレンダー追加ボタン）

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/detail/DetailScreen.kt`

- [ ] **Step 1: 実装**

```kotlin
package uk.nordtek.aiinbox.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.nordtek.aiinbox.R
import uk.nordtek.aiinbox.calendar.CalendarIntentBuilder
import uk.nordtek.aiinbox.data.db.ExtractedEvent
import uk.nordtek.aiinbox.data.db.InboxItem

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
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        val item = state.item
        if (item == null) {
            // loading or deleted
            return@Scaffold
        }
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

            if (item.tags.isNotEmpty() || item.people.isNotEmpty() || item.places.isNotEmpty()) {
                Card {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (item.category != null) Text("カテゴリ: ${item.category}")
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
            event.startMillis?.let { Text("開始: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))) }
            event.location?.let { Text("場所: $it") }
            Button(onClick = onAddToCalendar) {
                Text(LocalContext.current.getString(R.string.add_to_calendar))
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
git add app/src/main/kotlin/com/example/aiinbox/ui/detail/DetailScreen.kt
git commit -m "feat(ui): add read-only DetailScreen with calendar action"
```

---

## Task 28: Navigation + MainActivity

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/ui/navigation/Routes.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/MainActivity.kt`

- [ ] **Step 1: `Routes.kt` を作成**

```kotlin
package uk.nordtek.aiinbox.ui.navigation

object Routes {
    const val INBOX = "inbox"
    const val DETAIL = "detail/{id}"
    fun detail(id: String) = "detail/$id"
}
```

- [ ] **Step 2: `MainActivity.kt` を更新**

```kotlin
package uk.nordtek.aiinbox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import uk.nordtek.aiinbox.notification.NotificationHelper
import uk.nordtek.aiinbox.ui.detail.DetailScreen
import uk.nordtek.aiinbox.ui.detail.DetailViewModel
import uk.nordtek.aiinbox.ui.inbox.InboxScreen
import uk.nordtek.aiinbox.ui.navigation.Routes
import uk.nordtek.aiinbox.ui.theme.AiInboxTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openItemId = intent.getStringExtra(NotificationHelper.EXTRA_OPEN_ITEM_ID)
        setContent {
            AiInboxTheme {
                val nav = rememberNavController()
                val startDestination = if (openItemId != null) Routes.detail(openItemId) else Routes.INBOX

                NavHost(navController = nav, startDestination = startDestination) {
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

- [ ] **Step 3: ビルド確認**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: コミット**

```bash
git add app/src/main/kotlin/com/example/aiinbox/MainActivity.kt \
        app/src/main/kotlin/com/example/aiinbox/ui/navigation/
git commit -m "feat(ui): wire up Navigation with Inbox and Detail destinations"
```

---

## Task 29: 手動エンドツーエンド検証 + Plan 1完了コミット

**目的:** 実機/エミュレータにインストールし、Share→保存→要約→通知→詳細表示→カレンダー連携の一連の流れが動くことを目視確認する。

- [ ] **Step 1: 全テスト一発実行**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```
Expected: 両方ともすべてPASS

- [ ] **Step 2: APKをインストール**

```bash
./gradlew :app:installDebug
```
Expected: BUILD SUCCESSFUL、エミュレータ/実機にインストールされる

- [ ] **Step 3: 手動検証チェックリスト**

以下をエミュレータ/実機で順に実施し、すべて ✅ になることを確認：

- [ ] アプリをランチャーから起動 → 空のInbox画面が表示される
- [ ] Chrome等で任意のページの「共有」→ AI Inboxを選択
- [ ] Toast「保存しました」が表示される（共有元アプリに即座に戻る）
- [ ] アプリを開く → Inboxにアイテムが「処理中」バッジで表示される
- [ ] 数秒以内に通知「要約が完了しました」が出る
- [ ] 通知をタップ → 詳細画面が直接開く（タイトルがFakeで始まることを確認）
- [ ] 詳細画面で原文・要約・タグが表示される
- [ ] テキストに「__FAKE_EVENT__」を含めて共有 → 詳細画面に「📅」バッジ + イベントカード + 「カレンダーに追加」ボタンが出る
- [ ] 「カレンダーに追加」をタップ → 標準カレンダーアプリのプリフィル画面が開く（タイトル・場所・時刻が入っている）
- [ ] アプリを完全終了 → 再起動 → 保存されたアイテムが残っている（暗号化DBの永続性確認）
- [ ] `adb shell run-as uk.nordtek.aiinbox.debug ls databases/` で `inbox.db` が存在
- [ ] `adb shell run-as uk.nordtek.aiinbox.debug cat databases/inbox.db | grep -a "PLAINTEXT_MARKER" || echo OK` → 平文が見えない（暗号化確認）

- [ ] **Step 4: Plan 1完了マーカーコミット**

```bash
git commit --allow-empty -m "milestone(plan-1): foundation complete

- Project bootstrap (Gradle, Hilt, Compose, Room)
- Domain types and pure-Kotlin LLM helpers (TimeConverter, ContentHintDetector, PromptBuilder, LlmResponseParser)
- LlmEngine interface + FakeLlmEngine
- SQLCipher-encrypted Room DB with FTS5 search
- InboxRepository with edit-preserving result apply
- WorkManager + Hilt SummarizeWorker
- ShareReceiverActivity with text/plain intent filter
- Material 3 Inbox + read-only Detail screens
- Calendar Intent builder (no WRITE_CALENDAR permission)
- Basic completion notification

Next: Plan 2 - Real Gemma 4 LLM via MediaPipe + Foreground Service.
"
```

---

## Plan 1 自己チェック (writing-plans skill self-review)

**1. スペックカバレッジ:**

スペックの主要要件をPlan 1のタスクで実装：
- ✅ Share Intent受信 → DB保存 → 通知 (Tasks 17, 21, 22)
- ✅ 暗号化Room (SQLCipher + Keystore) (Tasks 13, 14)
- ✅ FTS5検索 (Tasks 15, 16)
- ✅ ユーザー編集フィールド保護 (Task 17の `applySummarizeResult`)
- ✅ カレンダーIntent (`Intent.ACTION_INSERT`、WRITE_CALENDAR不要) (Task 8)
- ✅ コンテンツタイプ判定 + プロンプト切替 (Tasks 4, 5)
- ✅ ISO8601 ↔ unix millis変換、終日対応 (Task 3)
- ✅ LLMレスポンスパーサ (コードフェンス耐性) (Task 6)
- ✅ LlmEngine抽象 (Task 7、Plan 2でMediaPipeに差し替え)

Plan 1スコープ外（Plan 2/3で対応）:
- ⏳ MediaPipe LLM Inference + Gemma 4 (Plan 2)
- ⏳ LlmInferenceService (Foreground Service、アイドル5分アンロード) (Plan 2)
- ⏳ ModelDownloadWorker + DL UI (Plan 2)
- ⏳ 検索バー・フィルタチップUI (Plan 3)
- ⏳ 詳細画面の編集・再要約・削除 (Plan 3)
- ⏳ 設定画面 (Plan 3)
- ⏳ イベント検出時のアクションボタン付き通知 (Plan 3)
- ⏳ 通知グルーピング (Plan 3)

**2. プレースホルダ走査:** TBD/TODO/「あとで」等は無し。すべてのStepにコード/コマンド/期待値を記載。

**3. 型整合性:**
- `LlmEngine.summarize()` の戻り値 `SummarizeResult` は Task 7 / 17 / 21 で一貫
- `InboxRepository.applySummarizeResult()` のシグネチャは Task 17 / 21 で一致
- `ItemStatus` の値 (PENDING / PROCESSING / COMPLETED / FAILED) は全タスクで統一
- `userEditedFields: Set<String>` のキー文字列 ("title", "summary", ...) は Task 17 で定義、Plan 3で使用
- Hiltバインディング: `LlmEngine` ← `FakeLlmEngine` (Task 18) は Plan 2 で `MediaPipeLlmEngine` に切替予定

---

## 実行ハンドオフ

Plan 1完成、 `docs/superpowers/plans/2026-05-02-android-ai-inbox-foundation.md` に保存。実行方法は2択：

**1. Subagent-Driven (推奨)** — タスクごとに新規subagentをディスパッチ、タスク間でレビュー、高速反復

**2. Inline Execution** — 本セッションで `executing-plans` skill を使ってバッチ実行、チェックポイント毎にレビュー

どちらにしますか？
