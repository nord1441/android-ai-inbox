# Android AI Inbox — Instrumented Test 実行手順書

このプロジェクトのPlan 1には、JVMだけでは検証できない **Instrumented Test (`androidTest/`)** が含まれます。これらは実機 or Androidエミュレータ上で動かす必要があります。

実装作業時はコンパイル確認のみ行い、テストの実行は本書の手順でユーザーがまとめて行います。

## 該当する AndroidTest

| Plan/Task | テストクラス | 検証内容 |
|---|---|---|
| Plan 1 / T14 | `app/src/androidTest/.../AppDatabaseEncryptionTest.kt` | Room + SQLCipher で暗号化DBに書け、ファイルバイト列に平文が現れない |
| Plan 1 / T16 | `app/src/androidTest/.../InboxDaoFtsTest.kt` | FTS5 仮想テーブルでの全文検索（日本語含む）が機能する |
| Plan 1 / T17 | `app/src/androidTest/.../InboxRepositoryTest.kt` | Repository CRUD、`userEditedFields` の保護、Flow更新の伝播 |
| Plan 1 / T21 | `app/src/androidTest/.../SummarizeWorkerTest.kt` | `SummarizeWorker` が `FakeLlmEngine` 経由で PENDING→COMPLETED 遷移する |
| Plan 2 / 多数 | （MediaPipeLlmEngine, LlmInferenceService, ModelDownloadWorker等） | 実装後追記 |
| Plan 3 / 多数 | （Inbox/Detail/Settings UIテスト） | 実装後追記 |

## 1. 環境変数の設定（毎セッション必要）

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$HOME/.local/opt/gradle-8.10/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

`~/.bashrc` に既に設定済みなので、新規シェルで開けば自動で適用されます。

## 2. AVD のセットアップ（初回のみ）

System imageとAVDは既にインストール済みです：

- System image: `system-images;android-35;default;x86_64`
- AVD名: `test35` （pixel_6 デバイス、Android 35）

確認：
```bash
avdmanager list avd
# Available Android Virtual Devices:
#   Name: test35  Device: pixel_6  Target: Android API 35
```

別AVDを作りたい場合：
```bash
echo "no" | avdmanager create avd -n my35 -k "system-images;android-35;default;x86_64" -d "pixel_6" --force
```

## 3. エミュレータの起動

### 3a. headless（CLI のみ）

Toolbox 内で headless 起動：
```bash
$ANDROID_HOME/emulator/emulator -avd test35 \
  -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -accel auto \
  > ~/emulator.log 2>&1 &
```

起動完了を待つ：
```bash
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>&1 | tr -d '\r')" = "1" ]; do
  sleep 5
done
echo "Boot complete"
adb devices
```

### 3b. Android Studio から起動（推奨）

1. Android Studio を開く（Flatpak版）
2. プロジェクトを開く: `/var/home/nord14541/android-ai-inbox`
3. Gradle Sync 完了を待つ
4. メニュー: Tools → Device Manager → Virtual → `test35` を選んで起動
5. または Run config の `Device` で `test35` を選択

Studio経由のほうがGUI付きで状態確認しやすく、ハードウェアアクセラレーション（KVM）も自動で使われます。

### 3c. 実機（USB接続）

- 実機の「開発者オプション」→「USBデバッグ」を有効化
- USBで接続
- `adb devices` で表示されればOK

## 4. AndroidTest の実行

### 全 AndroidTest を一括実行

```bash
cd /var/home/nord14541/android-ai-inbox
./gradlew :app:connectedDebugAndroidTest
```

結果は `app/build/reports/androidTests/connected/debug/index.html` で確認。

### 特定のテストクラスのみ

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.aiinbox.data.db.AppDatabaseEncryptionTest
```

### 複数のテストクラス

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.example.aiinbox.data
```

### Plan 1 完了時の検証バッチ

Plan 1 がすべて完了したら、以下を順に実行して全件PASS確認：

```bash
# 1. JVM unit tests (既に通っているはず、念のため)
./gradlew :app:testDebugUnitTest

# 2. Instrumented tests
./gradlew :app:connectedDebugAndroidTest

# 3. 全レポート確認
xdg-open app/build/reports/androidTests/connected/debug/index.html  # GUI環境で
# または
ls app/build/reports/androidTests/connected/debug/
```

## 5. エミュレータの停止

headless で起動した場合：
```bash
pkill -f "emulator -avd"
adb kill-server
```

Studio起動の場合：Device Manager で停止ボタン。

## 6. トラブルシューティング

### 起動が遅い／止まったように見える

- 初回起動は cold start で数分かかる。`~/emulator.log` を `tail -f` で確認
- KVM が有効か確認: `ls -la /dev/kvm`（rootアクセスでOK）、グループ `kvm` のメンバーか
- swiftshader を使っているなら遅い。ハードウェアGPUなら `-gpu host` の方が速いが、GLX/X11 が必要

### `INSTALL_FAILED_INSUFFICIENT_STORAGE`

AVDのデータパーティションを大きく：`avdmanager` で `--partition-size` を指定するか、Studio Device Manager の Edit から Internal Storage を増やす。

### `Test instrumentation process crashed`

- `Hilt` の `HiltTestRunner` が見えない場合、Plan 1 Task 14 の `app/src/androidTest/kotlin/com/example/aiinbox/HiltTestRunner.kt` が作成されているか確認
- `app/build.gradle.kts` の `testInstrumentationRunner = "com.example.aiinbox.HiltTestRunner"` が設定されているか確認

### SDKライセンスエラー

```bash
yes | sdkmanager --licenses
```

## 7. CI への組み込み（将来）

GitHub Actions / GitLab CI 等での自動実行は別途検討。AVDイメージを毎回DLするのは重いので、`actions/cache` でキャッシュするか、`reactivecircus/android-emulator-runner` を使うのが標準的。

## 8. 連絡先

このセッションで実装されたテストの内訳は `docs/superpowers/plans/2026-05-02-android-ai-inbox-foundation.md` の各タスクに記載されています。テスト失敗時はそのタスクの **Step 1** に書かれた「期待される失敗」と比較してください。
