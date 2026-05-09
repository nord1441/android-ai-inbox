# android-ai-inbox

端末内で完結する AI インボックス。共有メニューから流し込んだテキスト・スクリーンショットを、端末上で動作する小型 LLM と OCR が要約・分類・タグ付けし、暗号化ストレージに保存する。**ユーザーデータが外部に送信されることはない**。

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

## 設計目標

「ユーザーデータが端末から一切出ない」を最上位の制約として、すべての機能をその上に組む:

- 取り込みは Android の共有メニュー経由（テキスト / 画像）
- OCR は **ML Kit Text Recognition v2**（端末内）。Latin / Japanese を並行試行し長い方を採用
- 要約・分類・タグ・人物・場所・URL・予定の抽出は **Gemma 4 (e2b/e4b)** を **LiteRT-LM** で端末内推論
- DB は **SQLCipher (AES-256)** で全文暗号化、添付画像は **EncryptedFile (AES-256-GCM)** で暗号化保存
- FTS5 で要約・OCR・本文・タグを横断全文検索
- クラッシュ解析・利用統計・広告 SDK は組み込まない

## 外部通信について（透明性のため）

通信は以下のダウンロード方向のみ。ユーザーデータのアップロードは皆無:

- **初回起動時**: Gemma 4 重みを Hugging Face から（数百 MB）
- **OCR 初回利用時**: ML Kit のスクリプトモジュール（Latin / Japanese）を Google Play Services から（合わせて数〜十数 MB）

ダウンロード完了後は **機内モードでも全機能（取り込み・OCR・要約・検索・削除）が動作**する。

## 主な機能

- 共有メニューから 1 タップ取り込み（テキスト / 画像）
- 端末内 OCR（日本語 / Latin）
- 端末内 LLM による要約・タイトル・カテゴリ・タグ・人物・場所・URL・予定の抽出
- 全文検索（FTS5、要約 / OCR / 本文 / タグ横断、IME 変換中の中間文字列もフィルタ可能）
- 暗号化されたまま画像プレビュー（Coil カスタム Fetcher 経由）
- Markdown 形式での外部フォルダ同期（SAF 経由、ユーザー指定の暗号化ボリュームへ）
- Undo 付きソフト削除 → 確定後にファイル実体まで消去

## 必要環境

- Android 13 以上 (`minSdk = 33`)
- 推奨 RAM 8GB 以上 / 空き容量 1GB 以上
- Gemma 4 推論のため、一定の SoC 性能が必要

## ビルドと開発

ビルドツール:

- JDK 17
- Android SDK 35（`compileSdk = 35`, `targetSdk = 35`）
- Gradle wrapper 同梱（バージョンは `gradle/wrapper/gradle-wrapper.properties`）

debug ビルド & 端末インストール:

```bash
./gradlew :app:installDebug
```

unit test:

```bash
./gradlew :app:testDebugUnitTest
```

instrumentation test（実機 / エミュレータ必要）:

```bash
./gradlew :app:connectedDebugAndroidTest
```

release AAB 生成（署名設定は別途 — `docs/release-signing.md` 参照）:

```bash
./gradlew :app:bundleRelease
```

開発ワークフローやデバッグ手順の詳細は [`docs/development.md`](docs/development.md) を参照。

## アーキテクチャ概要

- UI: Jetpack Compose + Material3、Navigation Compose
- DI: Hilt
- 永続化: Room + SQLCipher（AES-256 全文暗号化）、`androidx.security.crypto` の EncryptedFile
- 非同期: Kotlin Coroutines + Flow
- バックグラウンド処理: WorkManager（Hilt 連携）。要約 / モデル DL / FS 同期 / tombstone GC が Worker
- LLM: LiteRT-LM (`Engine` API) で Gemma 4 重みをロード、推論
- OCR: Google ML Kit Text Recognition v2

各サブシステムの設計は `docs/superpowers/specs/` 以下の設計ドキュメント参照。

## ドキュメント

- 開発手順: [`docs/development.md`](docs/development.md)
- Play Store 公開準備チェックリスト: [`docs/playstore-release-checklist.md`](docs/playstore-release-checklist.md)
- Release 署名手順とバックアップ要件: [`docs/release-signing.md`](docs/release-signing.md)
- 設計ドキュメント: `docs/superpowers/specs/`
- 実装プラン: `docs/superpowers/plans/`
- 手動テストシナリオ: `docs/superpowers/manual-tests/`

## ライセンス

[Apache License 2.0](LICENSE)
