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
  - 含めるべき内容:
    - 「ユーザーデータ（受信テキスト・画像・OCR結果・要約）はすべて端末ローカルに保存され、外部に送信されない」
    - 「外部通信は **モデル/モジュールのダウンロードのみ**：① Gemma 4 重みを Hugging Face から、② ML Kit Text Recognition のスクリプトモジュール（Latin / Japanese）を Google Play Services から。いずれもダウンロード方向のみで、ユーザーデータのアップロードは伴わない」
    - 「初回ダウンロード以降は機内モードでも全機能（取り込み・OCR・要約・検索）が動作する」
    - 「暗号化方式: SQLCipher AES-256 + EncryptedFile (AES-256 GCM)」
    - 「削除手順（アプリ内個別削除 / アンインストール時全消去）」
    - 「問い合わせ先」

- [ ] **Data Safety フォームの記入**
  - 収集データ: なし
  - 共有データ: なし
  - ネット利用: あり（**ダウンロードのみ** — Gemma 重み from HF, ML Kit スクリプトモジュール from Play Services）。ユーザーデータの送信なし
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
  - 文言素案は下記「ストアリスティング文言素案」を参照
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

---

## ストアリスティング文言素案（オフラインファースト訴求）

設計目標は **「ユーザーデータが端末から一切出ない」**。それを訴求軸として固定する。

### Short description（80 文字以内、機械的にスペースも 1 文字としてカウント）

候補 A（機能寄り）:

> 受信メモ・スクショを端末内 AI が自動で要約・分類。データは端末から出ません。

候補 B（プライバシー寄り）:

> すべての処理が端末内で完結する AI インボックス。あなたのデータは外に出ません。

候補 C（機能 + 完全オフライン強調）:

> 端末内 AI で要約・OCR・検索。クラウド送信なし、機内モードでも動きます。

### Full description（4000 文字以内、構成案）

```
[キャッチ]
すべてが端末の中で完結する、プライベートな AI インボックス。

[一段落の本質説明]
他アプリから共有されたテキストやスクリーンショットを、端末内で動作する
小型 LLM (Gemma 4) と OCR が自動で要約・分類・タグ付け。あなたが入れた
情報は、要約結果も含めて、一切外部サーバに送信されません。

[主な機能]
- 共有メニューからテキスト・画像を1タップ取り込み
- 端末内 OCR（日本語 / Latin script）で画像内の文字を抽出
- 端末内 LLM が要約・タイトル・カテゴリ・タグ・人物・場所・URL・予定を抽出
- SQLCipher AES-256 で全文暗号化、添付画像も AES-256 GCM で暗号化保存
- FTS5 全文検索（要約・OCR・本文・タグを横断）
- 削除は Undo 付きソフト削除 → 確定後にファイル実体まで消去

[ネット接続について — 透明性のために]
本アプリの外部通信は、以下のダウンロードのみです:
  • 初回起動時: Gemma 4 モデル重みを Hugging Face から（数百 MB）
  • OCR 初回利用時: ML Kit のスクリプトモジュール（Latin / Japanese）を
    Google Play Services から（合わせて数〜十数 MB）

これらのダウンロードが完了すると、機内モードでも以下すべてが動作します:
  • 共有取り込み・OCR・要約・分類・検索・削除

ユーザーが本アプリに入れた情報（テキスト・画像・OCR結果・要約・タグ等）が
外部に送信されることは、いかなる場面でもありません。
クラッシュ解析・利用統計・広告 SDK も組み込んでいません。

[こんな人に]
- スクショやメモがどんどん溜まって整理が追いつかない人
- AI に要約してほしいが、データを外部サービスに渡したくない人
- 機内モードや電波の弱い環境でも使えるツールを求めている人

[必要環境]
Android 13 以上 / 推奨 RAM 8GB 以上 / 空き容量 1GB 以上
（Gemma 4 推論のため、一定の SoC 性能が必要です）
```

### Feature graphic / スクショの訴求コピー素案

- 「クラウド送信ゼロ」
- 「あなたのデータは端末から出ません」
- 「機内モードでも動く AI インボックス」
- 「OCR も要約も、すべて端末の中で」

### 注意事項（Play ポリシー観点）

- 「100% offline」「never connects to the internet」のような断定は **避ける**（モデル DL があるため、文字通りに取れば事実と異なり、審査リジェクトや誤認広告クレームの火種）
- 「ユーザーデータが外部送信されない」「処理は端末内で完結する」「初回ダウンロード以降はオフラインで動作する」のような **正確な表現** に統一する
- Data Safety フォームの申告内容と Listing 文言が一致していること（不一致は審査で指摘される）
