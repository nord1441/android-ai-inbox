# Google Play Store 公開準備チェックリスト

調査日: 2026-05-04 / `targetSdk = 35`, `minSdk = 33`, `versionCode = 1`

良好（Play 対応済み・触らなくて良い）:

- `targetSdk = 35` / `compileSdk = 35`（Play の API レベル要件クリア）
- release ビルドで `isMinifyEnabled = true`, `isShrinkResources = true`、ProGuard rules 配置済み
- `foregroundServiceType="dataSync"` 明示（Android 14+ 必須）
- WorkManager の `SystemForegroundService` にも type 付与（manifest merger で上書き）
- `applicationIdSuffix = ".debug"` で dev/release 分離
- 過剰 permission なし、`exported` 属性も明示的
- Crashlytics / Analytics / 広告 SDK 不在 → Data Safety フォームがシンプルに済む
- `allowBackup="false"` + `dataExtractionRules` で暗号化 DB を保護

---

## Critical（提出前に必ず）

- [ ] **`applicationId` を本番用に変更**
  - `app/build.gradle.kts:11, 15` の `namespace` と `applicationId` を `com.example.aiinbox` から自分のドメインベースに変更（例: `dev.nord14541.aiinbox`）
  - **一度 publish したら永久に変えられない**ので慎重に決める
  - パッケージリネームは IDE のリファクタ機能で全 import を一括更新

- [ ] **アプリアイコンの作成と差し替え**
  - 現状: `AndroidManifest.xml:14` で `android:icon="@android:drawable/sym_def_app_icon"`（システム placeholder）
  - 必要: Adaptive icon（foreground / background レイヤー）を `res/mipmap-anydpi-v26/` に配置
  - 加えて Play Console 提出用の 512×512 PNG ハイレゾアイコン

- [ ] **release 用 signing config の追加**
  - `app/build.gradle.kts:24-29` の `buildTypes.release` に `signingConfig` が未指定（unsigned）
  - upload keystore を生成 → `~/.gradle/gradle.properties` 経由でパスワード注入 → `signingConfigs.release` を追加
  - **keystore とパスワードはリポジトリに絶対コミットしない**
  - Play App Signing を有効化（Play Console 側で upload key を登録、本番署名は Play 管理）

## Important

- [ ] **アプリ名 `R.string.app_name` の確認**
  - Play Store 上のリスティング名と齟齬がないかチェック

- [ ] **モデル DL の Play ポリシー確認**
  - `ModelManager.kt`, `ModelDownloadWorker.kt` で Hugging Face から Gemma 重みを動的 DL
  - 重みは executable code ではないので「download executable code」ポリシーには該当しない（OK）
  - Data Safety フォームでネット利用（モデル DL のみ）を申告
  - 任意改善: Play Asset Delivery（Asset Pack）を使うとユーザ体験が良くなる（Phase 2 候補）

- [ ] **`versionCode` の運用ルール決定**
  - 現在 `versionCode = 1`, `versionName = "0.1.0"`
  - Play は同一 versionCode の AAB を弾くので release ごとに +1 必須
  - Git tag 連動 / 手動更新 / CI 自動化のどれにするか決める

- [ ] **`kotlinx-coroutines-play-services` 依存の見直し**
  - `app/build.gradle.kts:104`、Drive 連携破棄後に残っている可能性
  - 利用箇所が無ければ削除して APK サイズ削減

## Suggestion

- [ ] `LICENSE` ファイルをリポジトリ直下に配置（SPDX ID 明示、推奨: Apache-2.0 / MIT / GPL-3.0）
- [ ] `README.md` を拡充（機能、依存、ビルド手順、スクリーンショット）
- [ ] release ビルドで手動疎通スモーク（モデル DL → 共有 → 要約 → 削除 → FS 同期）
- [ ] Adaptive icon の foreground にブランドマークを入れる（Play のアイコンガイドライン）

---

## Play Console 側の準備（コードに手は入らないが提出に必須）

- [ ] **プライバシーポリシーの公開 URL**
  - GitHub Pages / Notion 公開ページ等で OK
  - 含めるべき内容: 「データはすべて端末ローカル保存」「外部通信は HF からのモデル DL のみ」「暗号化方式（SQLCipher AES-256 + EncryptedFile）」「削除手順」「問い合わせ先」

- [ ] **Data Safety フォームの記入**
  - 収集データ: なし
  - 共有データ: なし
  - ネット利用: あり（モデル DL のみ）
  - 暗号化: 保存時 AES-256（SQLCipher + EncryptedFile）、転送時 HTTPS
  - データ削除手段: アプリ内から個別削除可、アプリアンインストールで全消去

- [ ] **Content rating questionnaire**（IARC アンケート、数問）
- [ ] **Target audience**（13+ 等）
- [ ] **Permission の使用説明**（特に `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_DATA_SYNC`）
- [ ] **ストアリスティング素材**:
  - short description（80 文字）
  - full description（4000 文字）
  - phone screenshots × 最低 2 枚
  - feature graphic 1024×500 PNG
- [ ] **Internal testing track から開始** → Closed → Production の順で慣らす
- [ ] **Play App Signing 有効化**（upload key を生成して登録）

---

## 提案する着手順

1. `applicationId` の決定 → `namespace` + `applicationId` リネーム
2. Adaptive icon を作成して `res/mipmap-anydpi-v26/` に配置
3. release 用 keystore 生成 + `signingConfigs.release` 追加
4. `kotlinx-coroutines-play-services` の利用調査 → 不要なら削除
5. プライバシーポリシーを書いて公開（GitHub Pages 推奨）
6. release ビルド (`./gradlew bundleRelease`) で AAB 生成 → 手動疎通スモーク
7. Play Console で Internal testing track にアップロード → 自分の端末で動作確認
8. Closed testing（家族・友人を招待）→ Production
