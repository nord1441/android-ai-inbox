# 画像 / スクリーンショット取り込み — 設計ドキュメント

- 作成日: 2026-05-03
- ステータス: ドラフト（実装計画フェーズへ移行可）
- 関連: `2026-05-02-android-ai-inbox-design.md`（基盤設計）

## 1. 概要

既存の Android AI Inbox に「**他アプリからの画像共有**」と「**ハードキー/Quick Settings からのスクリーンショット即時取り込み**」を追加する。画像は OCR でテキスト化したうえで、既存の Gemma 4 (LiteRT-LM) 要約パイプラインに合流させる。

## 2. スコープ

### 2.1 新規機能

1. **画像共有受信**
   - 他アプリから `ACTION_SEND` / `ACTION_SEND_MULTIPLE` で `image/*` を受信
   - テキストと画像の同時共有にも対応
2. **スクリーンショット取り込み**
   - OS 標準のスクリーンショット撮影機能で撮影 → シェアシートから本アプリを選択 → 画像 Share 経路に合流して Inbox 化（§2.1 item 1 と同じ実装パスを共有）

### 2.2 既存への影響

- Inbox / 詳細 / 検索はそのまま使え、テキストアイテムと画像アイテムが混在表示
- LLM 抽出パイプライン (`SummarizeWorker`) は OCR 段を追加した上で既存ロジックを再利用
- DB スキーマは Migration 追加（`InboxItem.originalText` を nullable 化、`attachments` テーブル新設、FTS5 拡張）

### 2.3 スコープ外

- 動画・音声・PDF の取り込み
- 画像内の手書き文字・数式の特殊認識
- スクショの編集（クロップ・注釈）
- 画像内のヒット領域ハイライト（OCR テキストは FTS に入るが、画像内の領域可視化はしない）
- アクセシビリティサービス経由のサイレント撮影
- 多モーダル LLM への切替

## 3. 主要設計判断

| 判断点 | 採用案 | 理由 |
|---|---|---|
| 画像→意味抽出 | OCR + 元画像保存 (C) | 既存テキストLLM資産を再利用、ストレージ・配布サイズ最小、スクショ用途では文字情報が支配的 |
| スクショ撮影方式 | OS標準スクショ → シェアシート (代案 C 採用) | 実機テストで MediaProjection の同意ダイアログ繰り返しが UX 問題となり変更。OS 標準スクショ経路と実質同等の UX でアプリ側に撮影コードが不要 |
| 1共有あたりのアイテム数 | 1共有 = 1アイテム (複数添付) (A) | 連続スクショの「会話の流れ」を1要約にまとめられる |
| OCR → LLM 投入 | 直列OCR → 全文連結 → 1回 LLM (A) | 文脈の連続性、LLM呼び出し数最小、現行コンテキスト長で実用十分 |
| 起動経路 | Quick Settings タイル + Launcher 別エントリ (B) | アクションキー無し端末でも1タップ起動可能 |
| 画像保存 | EncryptedFile (B) + 正規化 JPEG q85 / 長辺2048px | 既存 SQLCipher 設計と暗号化レベル整合、ストレージ・OCR 速度を両立 |
| FTS5 拡張 | `attachments.ocr_text` も検索対象 (X) | OCR 由来の固有名詞を要約圧縮で失わずに残す |

## 4. アーキテクチャ

### 4.1 パッケージ構成（追加・変更）

```
com.example.aiinbox/
├── share/
│   └── ShareReceiverActivity.kt          ← 既存：image/* 対応で拡張
├── ocr/                                  ← 新規パッケージ
│   ├── OcrEngine.kt                      ← interface
│   └── MlKitOcrEngine.kt                 ← ML Kit Text Recognition (Latin + Japanese script)
├── data/
│   ├── db/
│   │   ├── Attachment.kt                 ← 新規 Entity
│   │   ├── AttachmentDao.kt              ← 新規 DAO
│   │   └── InboxItem.kt                  ← originalText nullable 化
│   ├── repository/
│   │   └── InboxRepository.kt            ← 添付追加 API、画像投入 API 拡張
│   └── storage/                          ← 新規パッケージ
│       └── EncryptedImageStore.kt        ← EncryptedFile ラッパー（保存・読込・削除）
├── llm/
│   └── ContentHint.kt                    ← SCREENSHOT / IMAGE_OCR を追加
├── work/
│   └── SummarizeWorker.kt                ← OCR 段を挿入、添付テキスト連結
└── ui/
    ├── inbox/InboxScreen.kt              ← サムネ表示・複数枚バッジ
    └── detail/DetailScreen.kt            ← 添付ギャラリー・全画面ビューア
```

### 4.2 主要コンポーネントの責務

#### `ShareReceiverActivity`（既存拡張）

- intent-filter に `image/*` (`ACTION_SEND`) と `image/*` (`ACTION_SEND_MULTIPLE`) を追加
- `EXTRA_STREAM` (Uri) または `EXTRA_STREAM` (List<Uri>) を取得
- 各 Uri → `ContentResolver.openInputStream` → 正規化 → `EncryptedImageStore.save()`
- `text/plain` も含む共有（`ClipData` 経由でテキスト + 画像）の場合、テキストは `originalText`、画像は添付として登録
- `createPendingItemWithAttachments` → `enqueueSummarize` → Toast → `finish()`
- Uri 由来の画像読込は `applicationScope` 内に投げるが、Activity が finish しても Uri 権限が有効である必要 → `intent.flags` の `FLAG_GRANT_READ_URI_PERMISSION` 確認 + 必要に応じ application context へ `grantUriPermission`

#### `MlKitOcrEngine`（`OcrEngine` 実装）

- ML Kit Text Recognition v2 (Latin script モジュール + Japanese script モジュール)
- 言語スクリプトは Play Services 経由で初回利用時に自動DL（既存のモデルDL UX とは独立、~10MB なので明示通知しない）
- API: `suspend fun recognize(bitmap: Bitmap): String`（ML Kit `InputImage.fromBitmap` 経由）
- Latin と Japanese の両方を試行し、長い方を採用（簡易的な言語自動選択）
- `TextRecognizer` インスタンスは1個キャッシュ、Worker のライフサイクル中保持
- Bitmap デコードは Worker 側で実施（EncryptedImageStore.read → BitmapFactory.decodeStream）

#### `SummarizeWorker`（既存拡張）

- DBから対象アイテム + 添付一覧取得
- 添付ごとに OCR 実行（直列）
- 連結フォーマット：

  ```
  [添付1: スクリーンショット]
  <OCR結果1>

  [添付2: 画像]
  <OCR結果2>

  [本文]
  <originalText>
  ```

- 既存 `LlmInferenceService.submitJob(joinedText, hint)` に投入
- `ContentHint` は「添付に SCREENSHOT があれば SCREENSHOT 優先、SHARED_IMAGE のみなら IMAGE_OCR、画像なし or テキスト併存なら従来判定」

#### `EncryptedImageStore`

- 保存先：`filesDir/attachments/<uuid>.jpg.enc`
- `EncryptedFile` (AndroidX Security) で AES-256 GCM 暗号化
- マスター鍵は `MasterKey.Builder` で AES256_GCM、Keystore 管理（既存 `KeystorePassphraseProvider` とは別ファイル鍵）
- API: `save(bytes): String (filename)` / `read(filename): InputStream` / `delete(filename)`
- Coil 用に `EncryptedImageFetcher` を追加（`InputStream` を返す Coil `Fetcher` 実装）

### 4.3 層の依存方向

```
ui ← (data + Coil経由でEncryptedImageStore読込)
share / screenshot → data + work
work → data + llm + ocr
data ↔ storage
llm ⊥ ocr （独立、Worker が両方を呼ぶ）
```

## 5. データモデル

### 5.1 `InboxItem` の変更

```kotlin
@Entity(tableName = "inbox_items", ...)
data class InboxItem(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "original_text") val originalText: String?,   // ← null 可に変更
    @ColumnInfo(name = "original_subject") val originalSubject: String?,
    @ColumnInfo(name = "source_app") val sourceApp: String?,         // 既存。"screenshot:capture" / 共有元 packageName
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

`originalText` のみ NULL 可に変更。他フィールドは既存と同じ。

### 5.2 新規 `Attachment` Entity

```kotlin
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = InboxItem::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("item_id"), Index("item_id", "ordering")],
)
data class Attachment(
    @PrimaryKey val id: String,                                  // UUID
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "ordering") val ordering: Int,            // 0,1,2...（共有時の順序）
    @ColumnInfo(name = "kind") val kind: AttachmentKind,         // SHARED_IMAGE / SCREENSHOT
    @ColumnInfo(name = "encrypted_filename") val encryptedFilename: String,  // <uuid>.jpg.enc
    @ColumnInfo(name = "mime_type") val mimeType: String,        // 正規化後 image/jpeg のみ
    @ColumnInfo(name = "width_px") val widthPx: Int,
    @ColumnInfo(name = "height_px") val heightPx: Int,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    @ColumnInfo(name = "ocr_text") val ocrText: String? = null,  // OCR 完了後に埋まる
    @ColumnInfo(name = "ocr_completed_at") val ocrCompletedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

enum class AttachmentKind { SHARED_IMAGE, SCREENSHOT }
```

### 5.3 関連付き取得用 DTO

```kotlin
data class InboxItemWithAttachments(
    @Embedded val item: InboxItem,
    @Relation(parentColumn = "id", entityColumn = "item_id")
    val attachments: List<Attachment>,
)
```

`InboxRepository.observeAll()` / `observeById()` / `observeFiltered()` の戻り型を `Flow<List<InboxItemWithAttachments>>` に拡張。

### 5.4 FTS5 拡張

既存 `inbox_fts` は `title / summary / originalText / tags / people / places` を含む。`attachments.ocr_text` を加えた FTS テーブルに再構築：

- `inbox_items` の INSERT/UPDATE/DELETE トリガに加え、`attachments` の変更時にも該当 `item_id` の FTS 行を再構築するトリガを追加
- 1アイテム複数添付 → FTS の1行に複数 OCR を `' '` で連結（`GROUP_CONCAT(ocr_text, ' ')`）

### 5.5 Migration 戦略

```sql
-- 1) inbox_items.original_text を NULL 可に変更（SQLite は ALTER で NULL制約変更不可なので
--    テーブル再作成）
CREATE TABLE inbox_items_new (
    id TEXT PRIMARY KEY NOT NULL,
    original_text TEXT,                -- NULL 可
    original_subject TEXT,
    source_app TEXT,
    received_at INTEGER NOT NULL,
    status TEXT NOT NULL,
    processing_attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    title TEXT,
    summary TEXT,
    category TEXT,
    tags TEXT NOT NULL,
    people TEXT NOT NULL,
    places TEXT NOT NULL,
    urls TEXT NOT NULL,
    event_title TEXT,
    event_start_millis INTEGER,
    event_end_millis INTEGER,
    event_location TEXT,
    event_confidence REAL,
    user_edited_fields TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);
INSERT INTO inbox_items_new SELECT * FROM inbox_items;
DROP TABLE inbox_items;
ALTER TABLE inbox_items_new RENAME TO inbox_items;
CREATE INDEX index_inbox_items_received_at ON inbox_items(received_at);
CREATE INDEX index_inbox_items_status ON inbox_items(status);
CREATE INDEX index_inbox_items_category ON inbox_items(category);

-- 2) attachments テーブル新設
CREATE TABLE attachments (
    id TEXT PRIMARY KEY NOT NULL,
    item_id TEXT NOT NULL,
    ordering INTEGER NOT NULL,
    kind TEXT NOT NULL,
    encrypted_filename TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    width_px INTEGER NOT NULL,
    height_px INTEGER NOT NULL,
    byte_size INTEGER NOT NULL,
    ocr_text TEXT,
    ocr_completed_at INTEGER,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(item_id) REFERENCES inbox_items(id) ON DELETE CASCADE
);
CREATE INDEX index_attachments_item_id ON attachments(item_id);
CREATE INDEX index_attachments_item_id_ordering ON attachments(item_id, ordering);

-- 3) FTS テーブル再構築（ocr_text を追加）
DROP TRIGGER IF EXISTS inbox_items_ai;  -- 既存トリガ（FtsCallback.kt 由来）
DROP TRIGGER IF EXISTS inbox_items_au;
DROP TRIGGER IF EXISTS inbox_items_ad;
DROP TABLE inbox_fts;
CREATE VIRTUAL TABLE inbox_fts USING fts5(
    id UNINDEXED,                       -- 既存 inbox_fts と同じく InboxItem.id を保持
    title, summary, original_text, tags, people, places, ocr_text,
    tokenize = 'trigram'
);
-- inbox_items の INSERT/UPDATE/DELETE トリガ再作成
--   ocr_text 列は (SELECT GROUP_CONCAT(ocr_text, ' ') FROM attachments WHERE item_id = new.id)
--   サブクエリで集計する
-- attachments の INSERT/UPDATE/DELETE トリガ追加
--   発火時に該当 item_id の inbox_fts 行を DELETE → INSERT で再構築
```

SQLCipher 環境での Room `Migration` クラスとして実装。マイグレーションテストは Repository (Instrumented) 層でカバー。

### 5.6 削除カスケード

- `InboxItem` 削除時、Room の `ForeignKey.CASCADE` で `attachments` 行は自動削除
- ただし **`EncryptedFile` の実体ファイルは別途明示削除が必要**
- Repository の `delete()` / `softDelete()` で先に attachments のファイル名を取得 → DB 削除 → ファイル削除
- Soft delete (Undo) 中はファイル残し、`finalizeDelete` でファイル削除

## 6. データフロー

### 6.1 スクリーンショット取り込み

削除（OS 標準スクショ → §6.2 のシェア経路に合流）

### 6.2 画像 Share 受信

```
[他アプリのシェアシート → AI Inbox]
        ↓
ShareReceiverActivity.onCreate()
  ├─ intent.action = ACTION_SEND or ACTION_SEND_MULTIPLE
  ├─ MIME = text/plain → 既存パス
  ├─ MIME = image/* → 新パス
  │     ├─ uri 配列を取得（SEND は1個、SEND_MULTIPLE は複数）
  │     ├─ application.applicationScope.launch {
  │     │     uriList.mapIndexed { idx, uri →
  │     │       ContentResolver.openInputStream(uri)
  │     │       → BitmapFactory.decodeStream
  │     │       → 正規化（JPEG q85, 2048px）
  │     │       → EncryptedImageStore.save(bytes)
  │     │       → Attachment(kind=SHARED_IMAGE, ordering=idx, ...)
  │     │     }
  │     │     Repository.createPendingItemWithAttachments(
  │     │         text=intent.getStringExtra(EXTRA_TEXT),  // テキスト併存ケース
  │     │         subject=intent.getStringExtra(EXTRA_SUBJECT),
  │     │         sourceApp=referrer?.host,
  │     │         attachments=...)
  │     │     WorkScheduler.enqueueSummarize(itemId)
  │     │   }
  │     ├─ Toast「保存しました」
  │     └─ finish()
```

### 6.3 SummarizeWorker（OCR 段挿入）

```
SummarizeWorker.doWork(itemId)
  ├─ Repository.getItemWithAttachments(itemId)
  ├─ for attachment in item.attachments:                  ← 直列OCR
  │     inputStream = EncryptedImageStore.read(attachment.encryptedFilename)
  │     bitmap = BitmapFactory.decodeStream(inputStream)
  │     ocrText = MlKitOcrEngine.recognize(bitmap)
  │     bitmap.recycle()
  │     Repository.updateAttachmentOcr(attachment.id, ocrText)
  │
  ├─ joinedText = JoinedTextBuilder.build(item.originalText, item.attachments)
  │     形式：
  │       [添付1: スクリーンショット]
  │       <ocr1>
  │
  │       [添付2: 画像]
  │       <ocr2>
  │
  │       [本文]
  │       <originalText>
  │
  ├─ contentHint = ContentHintDetector.detect(item, joinedText)
  │     → 添付に SCREENSHOT があれば SCREENSHOT 優先
  │     → 添付に SHARED_IMAGE のみなら IMAGE_OCR
  │     → 画像なし or テキスト併存なら従来判定
  │
  ├─ bindService(LlmInferenceService).submitJob(joinedText, contentHint)
  ├─ result = ...
  ├─ Repository.applySummarizeResult(itemId, result)
  ├─ NotificationHelper.showCompletion(item)
  └─ Result.success()
```

### 6.4 詳細画面表示

```
DetailScreen
  ├─ collectAsState: DetailUiState
  │     ├─ item: InboxItem
  │     ├─ attachments: List<Attachment>
  │     └─ ...
  ├─ AttachmentGallery（attachments 非空の場合に表示）
  │     ├─ Coil の AsyncImage(model = EncryptedFileImageRequest(attachment))
  │     │     ↓
  │     │   EncryptedImageFetcher (Coil Fetcher)
  │     │     ├─ EncryptedImageStore.read(filename) → InputStream
  │     │     └─ ImageDecoder にデコード委譲（メモリ上で復号）
  │     ├─ クリック → 全画面ビューア（ピンチズーム、スワイプで隣の添付）
  │     └─ 「OCR テキストを表示」展開で attachment.ocr_text 表示
```

### 6.5 Inbox リスト表示

```
InboxScreen の各行：
  ┌─────────────────────────────────────────┐
  │ [thumb1]  タイトル                      │
  │ [thumb2]  要約抜粋... （カテゴリ・経過時間） │
  │  +N枚    📅 [バッジ] PROCESSING        │
  └─────────────────────────────────────────┘

- 添付が1〜2枚：先頭2枚をミニサムネ（44dp）
- 3枚以上：先頭1枚 + "+N" バッジ
- 添付なし：従来通りテキストのみ
- Coil でサムネ表示時に内部で更にリサイズ（112px）
```

### 6.6 削除フロー

```
DetailScreen「削除」or InboxScreen スワイプ
  ↓
Repository.softDelete(itemId)
  ├─ getItemWithAttachments(itemId) → deletedBuffer に格納（item + attachments + ファイルパス）
  └─ DAO で行削除（CASCADE で attachments 行も消える、ファイル実体は残す）

[5秒 Snackbar Undo]
  ├─ Undo タップ → restoreDeleted(itemId)
  │     ├─ deletedBuffer から取得
  │     └─ DAO に再 insert（item + attachments、ファイル実体はそのまま再利用）
  └─ タイムアウト → finalizeDelete(itemId)
        ├─ deletedBuffer から削除
        └─ EncryptedImageStore.delete(filename) ×N
```

## 7. 権限・Manifest

### 7.1 追加権限

追加権限なし（`FOREGROUND_SERVICE_MEDIA_PROJECTION` は削除済み）。

**要求しない権限（明示）**：

- `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` — Share Intent 経由で `EXTRA_STREAM` (Uri) を受け取るので不要（Uri 権限が一時付与される）
- `WRITE_EXTERNAL_STORAGE` — 内部ストレージのみ使用
- アクセシビリティ — 使用しない

### 7.2 Manifest 追加宣言

```xml
<!-- ShareReceiverActivity の intent-filter 拡張 -->
<activity android:name=".share.ShareReceiverActivity" ...>
    <intent-filter>
        <action android:name="android.intent.action.SEND"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <data android:mimeType="text/plain"/>
        <data android:mimeType="image/*"/>     <!-- 追加 -->
    </intent-filter>
    <intent-filter>                            <!-- 追加 -->
        <action android:name="android.intent.action.SEND_MULTIPLE"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <data android:mimeType="image/*"/>
    </intent-filter>
</activity>
```

（ScreenshotCaptureActivity / ScreenshotCaptureService / ScreenshotTileService の Manifest 宣言は削除済み）

## 8. エラーハンドリング

| ケース | 挙動 |
|---|---|
| ContentResolver.openInputStream が SecurityException | 該当 Uri をスキップ、ログ警告。残りの添付で続行。全滅なら Toast「画像読込に失敗」+ DB 書き込みなし |
| 画像が `BitmapFactory.decodeStream` で null（破損 / 非対応形式） | 該当添付スキップ、Toast「一部の画像を読み込めませんでした」 |
| 正規化後ファイルサイズ 0 / 異常に大きい (>20MB) | 該当添付スキップ、ログ警告 |
| EncryptedFile 書き込み失敗（ストレージ不足） | Toast「ストレージ容量不足です」、DB 書き込みなし、書き込み済み一時ファイルを削除 |
| OCR 失敗（ML Kit モジュール未DL + オフライン） | OCR テキスト = `null` で続行。LLM には「[添付N: 画像]\n（テキスト抽出未完了）」と入れる。次回再要約で再 OCR 可 |
| OCR でテキスト 0 文字（写真等） | OCR テキスト = `""` で正常完了扱い。LLM には「[添付N: 画像]\n（テキストなし）」と入れる |
| OCR ＋本文が両方空 | LLM には投入せず、`title="画像"`、`summary="<添付N枚>"`、`category=null` で `COMPLETED` 確定 |
| 詳細画面で EncryptedFile 復号失敗（鍵破損 / ファイル消失） | プレースホルダ画像 + 「読み込めませんでした」表示、DB 行は残す |

### 8.1 プライバシー考慮

- ログ出力で OCR テキスト・画像のサムネをマスク（`itemId` と `attachmentId` のみ）
- ScreenshotCaptureService の Foreground 通知は **「スクリーンショット撮影中」** の最小表示。サムネ・本文を含めない
- 撮影前後のキャプチャを `MediaStore` には書き込まない（端末の標準ギャラリーに残らない）

### 8.2 リソース管理

- `Bitmap` は必ず `try { ... } finally { recycle() }` で解放
- OCR の ML Kit `TextRecognizer` インスタンスは `MlKitOcrEngine` で1個キャッシュ、Worker のライフサイクル中保持

## 9. テスト戦略

既存 `2026-05-02-android-ai-inbox-design.md` §10 のレイヤー別テスト方針に則りつつ、画像/スクショ系の追加テストを定義。

### 9.1 OCR/LLM 差し替え設計

```kotlin
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): String
}

class MlKitOcrEngine : OcrEngine     // 本番（ML Kit InputImage.fromBitmap）
class FakeOcrEngine(
    private val fixedResult: String = "fake-ocr-text"
) : OcrEngine                        // テスト用
```

Hilt テストモジュールで `MlKitOcrEngine` → `FakeOcrEngine` に差し替え。`SummarizeWorker` テストでは `FakeLlmEngine` + `FakeOcrEngine` の組み合わせ。

### 9.2 Unit (JVM)

| 対象 | 検証内容 |
|---|---|
| `BitmapNormalizer` (新規ヘルパ) | 入力解像度別（4K縦長 / 1080p / 100px極小 / 縦横比極端 / HEIC擬似）に対し JPEG q85, 長辺2048px に収まることを検証 |
| `JoinedTextBuilder` (新規ヘルパ) | 添付 0/1/N + 本文有無の組み合わせで、正しいフォーマットの連結文字列を生成 |
| `ContentHintDetector` 拡張 | 添付に SCREENSHOT/SHARED_IMAGE/混在/なし のケースで適切な `ContentHint` が返る |
| `LlmResponseParser` | 既存の回帰テスト継続 |

### 9.3 Repository (Instrumented)

- Room in-memory DB + 新マイグレーション適用後に：
  - `createPendingItemWithAttachments(text=null, attachments=N)` で item + N 行 attachment が atomic 挿入される
  - `softDelete` → `restoreDeleted` で attachments も復元、ファイルも残る
  - `softDelete` → `finalizeDelete` で attachments DB 行 + ファイルが消える
  - FTS5 拡張：`ocr_text` 更新時に検索ヒットする
  - Migration v(N) → v(N+1)：既存 `originalText NOT NULL` データがそのまま残る、新規 NULL 行を挿入できる
- `EncryptedImageStore`：
  - 保存 → 読込 で同一バイト列が返る
  - 異プロセス起動相当（インスタンス再作成）で復号できる
  - 削除後に読込は IOException

### 9.4 Worker (Instrumented)

`SummarizeWorker`：

- `FakeOcrEngine` + `FakeLlmEngine` 注入
- 添付ありアイテム投入 → OCR 完了で `attachment.ocrText` が埋まる、`item.status = COMPLETED`
- OCR 失敗（`OcrEngine.recognize` が throw）→ そのアイテムは `attachment.ocrText = null` で続行、LLM 投入される
- OCR + 本文両方空 → LLM 呼び出しなし、placeholder で COMPLETED

### 9.5 ScreenshotCaptureService (Instrumented)

- MediaProjection は実機でしかフルテストできないため、ロジック単体テスト を分離：
  - `BitmapToAttachmentPipeline` (新規ヘルパ) を切り出し、固定 Bitmap → 正規化 → 暗号化保存 → DB 行作成 → Worker enqueue が一連で動くことを検証
  - 黒画面検出ロジック（平均輝度判定）を unit test
- E2E（手動 / `@LargeTest`）：
  - 実機で Activity 起動 → 同意 → ファイル生成 → DB 行 → Worker 完了通知 まで通る

### 9.6 ShareReceiverActivity 拡張 (Instrumented)

- 単一画像 Uri：`Intent(ACTION_SEND).setType("image/jpeg").putExtra(EXTRA_STREAM, testUri)` でアイテム + 添付1件作成
- 複数画像 Uri：`ACTION_SEND_MULTIPLE` で添付 N 件、ordering が 0..N-1 で連番
- text + image 同時：本文と添付両方が保存される
- 破損画像 Uri：スキップされ、残りで成功

### 9.7 UI (Compose Instrumented)

- Inbox 行：添付 0/1/2/3+ のサムネ表示パターン（`+N` バッジ）
- 詳細画面：添付ギャラリーのスワイプ、全画面ビューア、OCR テキスト展開
- Coil + `EncryptedImageFetcher` のロード（テスト用 in-memory `EncryptedImageStore`）

### 9.8 実 OCR / 実 MediaProjection 統合 (`@LargeTest`、CI除外)

- 実画像（日本語スクショ / 英語記事スクショ / 写真）3種で `MlKitOcrEngine` の出力を回帰スナップショットに保存
- 実機で MediaProjection 同意 → 撮影 → 保存 → Inbox 表示まで通すスモーク手順を `manualtest/` ドキュメント化

### 9.9 性能・容量検証（`@LargeTest`）

- 4K スクショ × 8 枚を1アイテムで投入：
  - 正規化後の合計ストレージが妥当範囲（< 5MB）
  - OCR 連結後トークン数が LLM コンテキスト上限内に収まることをログ確認
  - SummarizeWorker 完了が 30秒以内（FAKE LLM 時）

## 10. 次フェーズ候補（スコープ外）

- ビジョン LLM への切替（Gemma 3n / Gemma Vision）— 多モーダル対応で「画像内容の意味理解」を強化
- アクセシビリティサービス経由のサイレント撮影（オプトイン）— 1タップ撮影体験を取り戻すための将来拡張
- 動画 / GIF / PDF の取り込み
- スクショの編集（クロップ・注釈）
- 画像内 OCR テキスト領域のハイライト表示
- 関連アイテムのグループ化（連続スクショを後付けでまとめる）
- アクションキー以外のジェスチャ（背面ダブルタップ等）対応
