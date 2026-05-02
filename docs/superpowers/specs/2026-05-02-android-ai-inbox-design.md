# Android AI Inbox — 設計ドキュメント

- 作成日: 2026-05-02
- ステータス: ドラフト（実装計画フェーズへ移行可）

## 1. 概要

Androidの **Share Intent (`ACTION_SEND` / `text/plain`)** で受け取ったテキストコンテンツを端末内に保存し、**オンデバイスLLM (Gemma 4)** で要約・情報抽出を行うアプリ。要約・抽出されたメタデータでの **検索**、スケジュール情報が含まれていた場合の **標準カレンダーアプリへの登録** をサポートする。

通信はモデルの初回ダウンロード時のみ。要約・抽出処理は完全にオンデバイスで完結する。

## 2. ターゲットと前提

- **対応Androidバージョン**: 13 (API 33) 以上
- **必要RAM**: 6GB以上
- **モデル**: Gemma 4 E4B (Q4_K_M, ~2.5GB) を 8GB+ RAM 端末では既定。6〜7GB RAM 端末では Gemma 4 E2B (Q4_K_M, ~1.3GB) にフォールバック
- **LLMランタイム**: MediaPipe LLM Inference API (`com.google.mediapipe:tasks-genai`)
  - ML Kit GenAI Prompt API はAICore対応端末に限定されるため、互換性重視でMediaPipeを採用
- **配布**: 公開配布想定（特定端末向けの個人最適化はしない、Play Store想定だが配布チャネルはMVP段階で確定不要）

## 3. ユーザー体験の柱

### 3.1 Share受信時のUX

- ShareシートでアプリX選択 → 共有元アプリに即座に戻る（モーダル/Activityが立ち上がらない）
- Toast「保存しました」のみ表示
- 要約はバックグラウンドで非同期に実行
- 完了時に通知でユーザーへ知らせる

### 3.2 通知

- **通常完了**: サイレント通知（`IMPORTANCE_LOW`）。チャンネル `summary_complete`
- **イベント検出時**: `IMPORTANCE_DEFAULT` 通知。「📅 カレンダーに追加」アクションボタン付き。チャンネル `event_detected`
- **複数件バースト**: `groupKey` で束ねる
- 通知タップで該当アイテムの詳細画面を開く

### 3.3 カレンダー登録

- LLMが日付/時刻/場所/イベント名を抽出 → 詳細画面または通知から「カレンダーに追加」
- `Intent.ACTION_INSERT` + `CalendarContract.Events.CONTENT_URI` でプリフィル
- ユーザーは標準カレンダーアプリのUIで最終確認・編集・保存
- **`WRITE_CALENDAR` 権限は要求しない**

### 3.4 検索

- Inbox画面に常設の検索バー
- SQLite **FTS5** による全文検索（タイトル / 要約 / 原文 / タグ / 人物 / 場所）
- フィルタチップ: カテゴリ × タグ × 「📅イベントあり」フラグ

### 3.5 ユーザー編集

- 詳細画面で要約・タグ・人物・場所・カテゴリ・イベント情報を手動編集可能
- 編集されたフィールドは `userEditedFields` セットに記録
- 「再要約」操作時、`userEditedFields` に含まれるフィールドはLLM結果で上書きしない

## 4. アーキテクチャ

### 4.1 モジュール構成

シングルモジュール (`:app`) 構成。マルチモジュール化はMVP範囲では過剰。パッケージとインターフェースで層を分離する。

```
com.example.aiinbox/
├── ui/              ← Compose画面とViewModel (inbox / detail / settings / theme)
├── share/           ← ShareReceiverActivity（Intent受け）
├── data/            ← Room DB + Repository + SQLCipher/Keystore鍵管理
│   ├── db/
│   ├── repository/
│   └── crypto/
├── llm/             ← LLM抽象とMediaPipe実装、Foreground Service、プロンプト
├── work/            ← WorkManager Workers (SummarizeWorker, ModelDownloadWorker)
├── notification/    ← 通知ヘルパ・チャンネル
├── calendar/        ← カレンダーIntent生成
└── di/              ← Hilt モジュール
```

**層の依存方向**: `ui → data + llm（経由はWorker）`、`data` と `llm` は直接依存しない（Workerが仲介）。

### 4.2 主要技術スタック

- **言語/UI**: Kotlin, Jetpack Compose, Material 3
- **DB**: Room + FTS5（手書きSQLでFTS5仮想テーブル定義） + SQLCipher（DB全体暗号化）
- **バックグラウンド**: WorkManager（Workerは永続化）+ Foreground Service（モデルライフサイクル）
- **DI**: Hilt
- **LLM**: MediaPipe LLM Inference API
- **非同期**: Coroutines + Flow

### 4.3 LLM推論プロセスの寿命管理（採用アプローチ）

「**ハイブリッド: アイドルタイムアウト付き Foreground Service**」を採用。

- `LlmInferenceService`（Foreground Service）がジョブキュー駆動で動作
- ジョブ到着 → モデルがアンロード状態ならロード → 推論実行
- ジョブキューが空になって **5分経過** したら自動でモデルアンロード
- 連続Shareされてもバースト中はモデルが乗り続け、推論レイテンシが最小化される
- アイドル時はRAMを2.5GB占有しない

理由：典型的な使い方は「気になる記事を3〜5件まとめてShare」のバースト型と予想される。バースト先頭でモデルロード（5〜15s）を払えば、後続は推論のみで完了。

### 4.4 主要コンポーネント

#### Share受信
**`ShareReceiverActivity`**（`Theme.NoDisplay`、UIなし）
- Manifestで `<intent-filter>` `ACTION_SEND` + `text/plain` を宣言
- `EXTRA_TEXT`（必須）/ `EXTRA_SUBJECT`（任意）/ 共有元 `packageName` を取得
- `InboxRepository.createPendingItem()` で `status=PENDING` のアイテムをDBに保存
- `WorkManager.enqueueUniqueWork(itemId, REPLACE, SummarizeWorker)`
- Toast「保存しました」 → `finish()`

#### LLM層

```kotlin
interface LlmEngine {
    suspend fun ensureLoaded(modelVariant: ModelVariant)
    suspend fun unload()
    suspend fun summarize(text: String, contentHint: ContentHint): SummarizeResult
    val isLoaded: StateFlow<Boolean>
}

class MediaPipeLlmEngine : LlmEngine    // 本番実装
class FakeLlmEngine : LlmEngine         // テスト用差し替え
```

**`MediaPipeLlmEngine`** は `LlmInference.LlmInferenceOptions` で `.task` ファイルをロード、`generateResponseAsync` を Flow にラップ。プロンプトはJSON出力指示、パース失敗時は1回だけリトライ後に素のテキスト要約にフォールバック。

**`LlmInferenceService`**（Foreground Service）
- 内部に `LlmEngine` を保持、ジョブキュー（`Channel<Job>`）で順次処理
- Bind経由で `submitJob(text, hint): Deferred<SummarizeResult>` API公開
- アイドル5分で `unload()` 呼出（`stopSelf()`）

#### Worker層

**`SummarizeWorker`**（`CoroutineWorker`）
- DBから対象アイテム取得 → `LlmInferenceService` にbind → `submitJob` → 結果でDB更新 → 通知発行
- `Constraints.BATTERY_NOT_LOW = true`、Expedited Work
- 失敗時は1回だけ自動リトライ（コンテキスト長を半減）

**`ModelDownloadWorker`**
- 端末RAM判定で `E4B` または `E2B` を選択
- HuggingFace等から `.task` ファイルをDL
- DL進捗を Foreground Notification で表示
- Range-GETでレジューム可能

### 4.5 データモデル

```kotlin
@Entity(tableName = "inbox_items")
data class InboxItem(
    @PrimaryKey val id: String,                        // UUID
    val originalText: String,
    val originalSubject: String?,
    val sourceApp: String?,                            // 共有元 packageName
    val receivedAt: Long,                              // unix millis (UTC)

    val status: ItemStatus,                            // PENDING / PROCESSING / COMPLETED / FAILED
    val processingAttempts: Int = 0,
    val lastError: String? = null,

    val title: String? = null,
    val summary: String? = null,
    val category: String? = null,                      // 仕事/個人/ニュース/買い物/その他
    val tags: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val urls: List<String> = emptyList(),

    @Embedded(prefix = "event_")
    val event: ExtractedEvent? = null,

    val userEditedFields: Set<String> = emptySet(),
    val updatedAt: Long
)

data class ExtractedEvent(
    val title: String,
    val startMillis: Long?,                            // 時刻不明なら null（終日）
    val endMillis: Long?,
    val location: String?,
    val confidence: Float                              // 0..1
)

enum class ItemStatus { PENDING, PROCESSING, COMPLETED, FAILED }
```

**インデックス**: `receivedAt DESC`, `status`, `category`

**FTS5仮想テーブル `inbox_fts`**: `title / summary / originalText / tags(joined) / people(joined) / places(joined)` を含む。`inbox_items` 変更時にトリガーで同期。Room 2.6時点でFTS5公式サポートが限定的なため、`@DatabaseView` + マイグレーションでの手書き `CREATE VIRTUAL TABLE` で実装。

**TypeConverter**: `List<String>` / `Set<String>` ↔ JSON、`ItemStatus` ↔ String、`ExtractedEvent` は `@Embedded`。

### 4.6 暗号化（SQLCipher）

- **DB全体をSQLCipherで暗号化**
- パスフレーズ：初回起動時にランダム32Bを生成し、`EncryptedSharedPreferences` (AndroidX Security) で保存。マスターキーは Android Keystore 管理
- `AndroidManifest.xml` で `android:allowBackup="false"` を設定し、機種変更時のバックアップ経由でDB/鍵が外部に出ないようにする

## 5. プロンプトと構造化出力

### 5.1 プロンプト戦略

- **コンテンツタイプ自動判定**: 入力テキストの先頭から `ContentHint` を推定（URL/ヘッダ/メタ情報がある→Web記事、`@`/`発信者:`等→チャット/メール、それ以外→メモ）
- ヒントごとにシステムプロンプトの抽出指示を切り替え（イベント抽出は特に「日付/時刻表現」の解釈ルールをチャット系で強化）

### 5.2 構造化出力

LLMには以下の **JSON Schema** を出力させる：

```json
{
  "title": "string (≤30 chars)",
  "summary": "string (≤200 chars)",
  "category": "仕事|個人|ニュース|買い物|その他",
  "tags": ["string"],
  "people": ["string"],
  "places": ["string"],
  "urls": ["string"],
  "event": {
    "title": "string",
    "start_iso": "ISO8601 or null",
    "end_iso": "ISO8601 or null",
    "location": "string or null",
    "confidence": 0.0
  } | null
}
```

- パース失敗時は1回だけ再プロンプト（フォーマット違反例示）
- 再失敗時は `summary` のみ非構造化テキストとして保存、他フィールドは `null`
- **時刻表現の正規化**: LLMはタイムゾーン未指定の場合、端末ローカルTZを基準に `start_iso` / `end_iso` を出力するよう指示。アプリ側で `LocalDateTime` → 端末TZ → unix millis に変換し、DBの `startMillis` / `endMillis` に保存
- **時刻不明時**: LLMは `start_iso` を `YYYY-MM-DD` のみで出力可（時刻なし＝終日扱い）。アプリ側で「日付のみ」を判定し、`startMillis = 当日0:00`、`endMillis = null` で終日イベントとして保存

## 6. データフロー

### 6.1 Share受信 → 要約完了

```
ShareReceiverActivity
  ├→ Repository.createPendingItem() [status=PENDING]
  ├→ WorkManager.enqueueUniqueWork(SummarizeWorker)
  └→ Toast "保存しました" → finish()

SummarizeWorker (バックグラウンド)
  ├→ Repository.getItem(itemId)
  ├→ bindService(LlmInferenceService)
  ├→ service.submitJob(text, hint)
  │     └→ LlmInferenceService: ensureLoaded → engine.summarize → JSON返却
  ├→ Repository.updateItem(status=COMPLETED, ...抽出結果)
  ├→ NotificationHelper.showCompletion(item)
  │     └→ event検出時はカレンダー追加アクション付き
  └→ Result.success()
```

### 6.2 Inbox表示 + 検索

```
InboxScreen
  ↓ collectAsState
InboxViewModel.uiState
  ↓
Repository.observe(filter, query): Flow<List<InboxItem>>
  ├→ query空 → DAO通常クエリ（カテゴリ/タグでフィルタ + receivedAt降順）
  └→ query非空 → FTS5 MATCH クエリ
```

### 6.3 カレンダー登録

```
[詳細画面 or 通知アクション] → onAddToCalendar(event)
CalendarIntentBuilder.build(item, event):
  Intent(ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
    putExtra(TITLE, event.title)
    putExtra(EVENT_LOCATION, event.location)
    putExtra(BEGIN_TIME, event.startMillis)
    putExtra(END_TIME, event.endMillis)
    putExtra(DESCRIPTION, item.summary + "\n\n[原文抜粋]\n" + item.originalText.take(500))
  }
→ startActivity()
→ 標準カレンダーアプリで確認・編集・保存
```

### 6.4 初回モデルダウンロード

```
MainActivity 起動
  └ ModelManager.checkAvailability()
      ├ あり → MainContent
      └ なし → ModelDownloadScreen
          ├ ActivityManager.MemoryInfoでRAM判定 (≥8GB→E4B, <8GB→E2B)
          ├ Wi-Fi未接続なら確認ダイアログ
          └ WorkManager.enqueue(ModelDownloadWorker)
              └ Foreground Notification でDL進捗表示
```

### 6.5 ユーザー編集 + 再要約

```
詳細画面でフィールド編集
  → DetailViewModel.onEditField(field, value)
      → Repository.updateField(id, field, value, addToUserEdited=true)

詳細画面で「再要約」タップ
  → Worker再エンキュー
      → MediaPipeLlmEngine.summarize() → 新結果
      → Repository.updateItemPreservingUserEdits(itemId, newResult)
          → userEditedFields に含まれるフィールドはスキップ
```

## 7. UI構造

3画面構成（Compose + Material 3）。

### 7.1 Inbox画面

- 上部: 検索バー
- その下: フィルタチップ（カテゴリ複数選択 × タグ複数選択 × 📅イベントあり）
- リスト: `LazyColumn`、各アイテムは `title` / `summary`抜粋 / バッジ（カテゴリ・イベント有無・PROCESSING/FAILEDステータス）/ 経過時間

### 7.2 詳細画面（フルScreen）

上から順に：
1. 抽出された`title`（編集可）
2. 検出された`event`カード（あれば「📅 カレンダーに追加」CTA付き、`confidence`が低い場合は警告アイコン）
3. `summary`（編集可）
4. エンティティチップ群（`people` / `places` / `urls`、各々編集可）
5. カテゴリ・タグ（編集可）
6. 折りたたみで原文（読み取り専用）
7. アクション: コピー / 再要約 / 削除（5秒Undo付き）

### 7.3 設定画面

- モデル状態（バージョン、サイズ、再ダウンロードボタン）
- DB使用量
- バージョン情報・ライセンス
- （将来用）モデルバリアント手動切替

## 8. エラーハンドリング

| ケース | 挙動 |
|---|---|
| `EXTRA_TEXT` 空/null | Toast「テキストが見つかりません」、DB書き込みなし |
| 巨大テキスト（>50KB） | 全文DB保存、要約時はLLMコンテキスト上限まで切り詰め+「[要約は冒頭のみ]」マーカー |
| 連続Share | `enqueueUniqueWork` でidempotent、UUIDで重複なし |
| モデル未DLでShare受信 | アイテムは`PENDING`保存、Toast「モデル準備後に処理します」、Worker側でモデル待ち |
| DL中プロセスKill | WorkManagerが自動レジューム、Range-GETで途中再開 |
| DL失敗 | 指数バックオフで最大3回自動リトライ、その後は設定画面の「再DL」ボタン |
| ストレージ不足 | DL前に `StatFs` で空き容量チェック、不足なら明示エラー画面 |
| LLM OOM/タイムアウト | コンテキスト半減で1回再試行、失敗なら`status=FAILED`、ユーザー手動リトライ可 |
| JSON出力パース失敗 | プロンプト修正で1回再試行、失敗なら`summary`のみ非構造化保存 |
| Service起動/bind失敗 | Worker `Result.retry()`、WorkManagerが指数バックオフ |
| カレンダーアプリなし | `resolveActivity()`がnullなら「カレンダーアプリが見つかりません」Snackbar |
| 通知権限拒否 | サイレント失敗（処理は進む）、初回利用時のみ権限要求 |
| 削除アクション | 5秒Snackbar Undo、それ以降確定削除 |

### 8.1 プライバシーフェイルセーフ

- ログ出力では `originalText` / `summary` をマスク、`itemId` と `status` のみ
- MVPではFirebase Crashlytics等の自動クラッシュレポーターを **導入しない**（本文流出リスクの根本回避）
- `android:allowBackup="false"` 設定

## 9. 権限

`AndroidManifest.xml` で宣言する権限：

- `INTERNET` — 初回モデルDL用
- `POST_NOTIFICATIONS` (Android 13+) — 通知用、初回利用時にランタイム要求
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` — `LlmInferenceService` および `ModelDownloadWorker` 用

**要求しない権限**:
- `WRITE_CALENDAR` — `Intent.ACTION_INSERT` 経由のため不要
- `READ_EXTERNAL_STORAGE` — Share Intentでテキスト直接受信のため不要
- `RECEIVE_BOOT_COMPLETED` — WorkManagerが自動復帰

## 10. テスト戦略

### 10.1 LLM差し替え設計

`LlmEngine` インターフェースで本番(`MediaPipeLlmEngine`)とテスト(`FakeLlmEngine`)を切替。Hiltテストモジュールで差し替え、ViewModel/Worker/Repositoryのテストでは実LLMを呼ばない。

### 10.2 レイヤー別テスト

**Unit (JVM)**
- `PromptBuilder` — content type判定、テンプレート埋め込み、文字数上限
- `LlmResponseParser` — 期待JSON / 壊れたJSON / 部分JSON。回帰用フィクスチャを `test/resources/responses/` に蓄積
- `CalendarIntentBuilder` — `ExtractedEvent` → `Intent` マッピング
- `EventValidator` — 日付パース（"明日10時"、"来週月曜"、ISO8601、自然文）。重点的に
- `KeystorePassphraseProvider` — Robolectricで初回生成・再取得の冪等性

**Repository (Instrumented)**
- Room in-memory DB + FTS5仮想テーブル作成スクリプト適用
- 検索（FTS5マッチ、日本語含む）、フィルタ組み合わせ、Flow更新の伝播
- `userEditedFields` 保護ロジック

**Worker (Instrumented)**
- `WorkManagerTestInitHelper` 使用
- `SummarizeWorker` に`FakeLlmEngine`注入、PENDING→COMPLETED遷移、失敗→FAILED確定
- `ModelDownloadWorker` は `MockWebServer` で成功/失敗/レジューム検証

**ViewModel (JVM)**
- `kotlinx-coroutines-test` の `runTest`、`Turbine` で `StateFlow` 検証
- フィルタ・検索クエリ変更時の状態遷移、エラー状態

**UI (Compose Instrumented)**
- `createComposeRule()` で主要画面のスナップショット
- Inbox空状態、各ステータスバッジ、検索バー、フィルタチップ
- 詳細画面のイベントCTA、編集→保存フロー

### 10.3 実LLM統合テスト

`@LargeTest` タグで隔離、CIには載せない：
- 実機/エミュレータ + 実モデルファイルで、Web記事 / チャットログ / 日本語メモの3パターンに対する妥当性チェック
- 結果スナップショットを `androidTest/resources/llm-snapshots/` に保存、要約欠損やJSON破壊などの明らかな品質劣化を検出
- リリースビルド前に手動実行

### 10.4 CI

- Unit + Repository + Worker + ViewModel + UI(Compose) すべて実行
- 実LLMテストは `@LargeTest` で除外

## 11. スコープ外（次フェーズ候補）

- セマンティック検索（オンデバイス埋め込みモデル）
- アクション項目（TODO）抽出 / Key Points箇条書き
- カテゴリ別タブ・カレンダービューの追加
- BiometricPromptによる起動時ロック
- モデルの手動切替UI
- 多言語UI（日本語のみで開始）
- Cloud LLMフォールバック（プライバシー要件と矛盾するため当面なし）
