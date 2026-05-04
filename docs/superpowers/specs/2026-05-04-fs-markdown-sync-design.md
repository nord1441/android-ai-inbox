# Filesystem + Markdown Inbox sync

## Goal

Let a single user keep their AI Inbox visible across multiple devices
by exporting each item as a plain Markdown file (with attachments) into
a user-chosen directory, and importing new items that the same
directory grows. The directory itself is synced device-to-device by
whichever tool the user already uses (Syncthing, iCloud Files, Drive
desktop, an SD card, etc.) — this app does not run a network protocol.

The Markdown files are also useful as a standalone, human-readable
backup the user can browse with Obsidian / Logseq / any editor without
the app installed.

## Non-goals

- Bidirectional **edit** propagation. Editing an item's title or tags
  on device A and another field on device B at the same time is
  outside scope: each item is edited only on the device that created
  it. (Add-only and delete-only flows are propagated.)
- Encryption of the exported files. The whole point of the export is
  human readability and tool interoperability; encrypting the bytes
  would defeat both.
- An in-app conflict UI. Deletes propagate via tombstones; edits are
  not propagated and therefore cannot conflict.
- Network sync, OAuth, account linking. Movement of files between
  devices is delegated to whatever tool watches the chosen directory.
- The on-device LLM model file (kept manual via `scripts/push-model.sh`).
- Sync of attachments not stored as `EncryptedImageStore` files (the
  app has none today, this is just a forward note).

## Architecture

### Components

| Component | Responsibility |
|---|---|
| `FsSyncFolderStore` | Persists the user's chosen SAF tree URI through `EncryptedSharedPreferences`; surfaces the URI plus a friendly display name to the UI. |
| `MarkdownExporter` | Pure-ish encoder: takes an `InboxItem` + its `Attachment`s and produces the YAML frontmatter + body Markdown bytes. No filesystem I/O. |
| `MarkdownImporter` | Pure-ish decoder: takes the Markdown bytes and returns a parsed envelope (or a typed parse error). No filesystem I/O. |
| `SafFolderAccess` | Thin wrapper over `DocumentFile` that handles atomic write (`.tmp` → `renameTo`), `attachments/` subdirectory creation, listing `.md` files, reading bytes, deleting files. |
| `FsSyncEngine` | Orchestrates one sync run: list `.md` files → diff against local DB by id → for each diff bucket call exporter / importer / file-mutator. Has the same "compute then apply" shape as the Drive `SyncEngine` had, but the diff axis is "exists in DB" / "exists on disk" rather than LWW. |
| `FsSyncCoordinator` | Trigger dedupe via `WorkManager.enqueueUniqueWork(name = "fs_sync", policy = KEEP)`. Single entry point. |
| `FsSyncWorker` (CoroutineWorker) | The WorkManager executor. Calls into the engine, updates `FsSyncStateRepository`. |
| `FsSyncStateRepository` | UI state SSOT (idle / running / error, last sync timestamp). |
| `FsTombstoneGcWorker` | Daily periodic worker that physically removes tombstoned rows older than 30 days, locally + in the SAF directory. |

### Boundaries and rationale

`MarkdownExporter` and `MarkdownImporter` are split from the I/O so the
encoding/decoding can be unit-tested with no Android dependencies — the
SAF / DocumentFile half is the awkward part to test, so the pure half
needs to carry as much of the testable logic as possible.

`SafFolderAccess` exists because `DocumentFile` is verbose and
exception-prone (children lookup, write through `ContentResolver`,
rename). The wrapper hides the SAF noise from the engine.

## Data model changes

### DB migration

None for the engine itself: the existing `inbox_items.deleted_at` and
`inbox_items.last_synced_at` columns from the (since-discarded) Drive
sync work are not in the schema, but we don't need them either.
File-system sync's "diff" is "what's on disk vs what's in the DB,"
both sides keyed by `id` — there is no remote authoritative
timestamp to compare against.

The only persistence the new feature needs is:

- **`fs_sync_state`** singleton row (new table at schema version 3):

  ```sql
  CREATE TABLE fs_sync_state (
      id INTEGER PRIMARY KEY NOT NULL CHECK(id = 1),
      folder_uri TEXT,
      last_full_sync_at INTEGER
  );
  INSERT INTO fs_sync_state (id, folder_uri, last_full_sync_at) VALUES (1, NULL, NULL);
  ```

  Folder URI is stored both here (for the engine to look up) and in
  `EncryptedSharedPreferences` via `FsSyncFolderStore` (for fast UI
  reads without going through Room). The two stay in sync because
  changes flow through one path: the Settings ViewModel writes both
  on directory pick.

- **Tombstone column on `inbox_items`** (new at schema version 3):

  ```sql
  ALTER TABLE inbox_items ADD COLUMN deleted_at INTEGER;
  ```

  Used both by the UI ("don't show tombstoned rows in the inbox list")
  and by the export pass ("emit a tombstone-shaped Markdown for these,
  then strip the body").

The migration handling itself follows the project's pre-release stance
(no `MIGRATION_2_3`; recovery is reinstall — see
`docs/development.md → Release readiness`). The `3.json` schema
export still ships so Room's compile-time validation passes.

## Filesystem layout

The user picks a directory through `ACTION_OPEN_DOCUMENT_TREE`. The
URI is held with `Intent.FLAG_GRANT_READ_URI_PERMISSION |
FLAG_GRANT_WRITE_URI_PERMISSION` and persisted via
`ContentResolver.takePersistableUriPermission`.

Inside that directory:

```
<chosen-dir>/
├── 2026-05-04-f08528b4-c333-4227-843c-3152e74cfef3.md
├── 2026-05-04-9f1...md
├── ...
└── attachments/
    ├── dbf1b467-919d-457f-93da-5b24b3eba959.jpg
    ├── ...
```

- **Filename**: `YYYY-MM-DD-<inbox_items.id>.md`. The date is
  `received_at` formatted in the device's local timezone — humans
  group by day, ids stay unique. Title changes don't rename the file
  (id-keyed reconciliation).
- **`attachments/`** holds the plain bytes of each attachment.
  Filename is `<attachments.id>.<ext>` where `<ext>` is derived from
  `mime_type` (`image/jpeg` → `.jpg`, `image/png` → `.png`,
  fallback `.bin`).
- **`.tmp` files** are write-in-progress markers (atomic write,
  see below). Importers ignore anything not ending in `.md`.

## Markdown payload format

```markdown
---
id: f08528b4-c333-4227-843c-3152e74cfef3
received_at: "2026-05-04T12:35:34+09:00"
updated_at: "2026-05-04T12:39:26+09:00"
status: COMPLETED
title: ほっともっと注文受取案内
category: 買い物
tags: [ほっともっと, 注文, 受取]
people: [福島 啓能様]
places: [ほっともっと]
urls: []
event:
  title: 商品受取
  start: "2026-05-04"
  end: null
  location: ほっともっと
  confidence: 0.9
source_app: com.android.intentresolver
attachments:
  - id: dbf1b467-919d-457f-93da-5b24b3eba959
    file: attachments/dbf1b467-919d-457f-93da-5b24b3eba959.jpg
    mime: image/jpeg
    width_px: 800
    height_px: 600
    byte_size: 482311
    ocr_text: |
      ほっともっと
      ご注文ありがとうございます。
      受取番号: 73T
      受取予定日: 5月4日(月) 12:05
---

ほっともっとからの注文受取に関する案内です。受取番号は73Tで、受取予定日は5月4日(月) 12:05です。商品受取の際は、受取番号と注文内容の確認が必要です。
```

- **YAML frontmatter** holds every field needed to round-trip an
  `InboxItem` + its `Attachment`s. Encoded via
  `kotlinx-serialization-yaml` so we don't hand-construct YAML
  strings (escaping bug surface).
- **Body** holds only `summary`. The `original_text` is omitted
  because the share-sheet flow's text is almost always redundant with
  the LLM summary; it can be added back as a `## 元テキスト` section
  later if needed.
- **Encoding**: UTF-8 without BOM, LF line endings.
- **Schema version** is implicit (`schema_version: 1` field could be
  added if the format ever changes; for now omitted).

### Tombstone shape

When an item is soft-deleted, the same file is rewritten with body
stripped and frontmatter reduced to:

```markdown
---
id: f08528b4-c333-4227-843c-3152e74cfef3
status: DELETED
deleted_at: "2026-05-04T14:00:00+09:00"
received_at: "2026-05-04T12:35:34+09:00"
---
```

Importers see `status: DELETED` and either tombstone the local row
(if it existed) or skip insertion (if they had not yet seen the id).

The corresponding files in `attachments/` are deleted from the SAF
directory at the same time so the bytes don't linger.

## Sync algorithm

A single sync run, kicked off by `FsSyncWorker.doWork()`:

```
1. Resolve the SAF tree URI (bail with success if not configured).
2. List all `.md` files in <chosen-dir>. Build a map (id → DocumentFile).
3. Read all local rows including tombstones via repository.allRefs().
4. For each id in (local ∪ remote), classify and act:
     - **local-only** (not on disk yet, alive or tombstoned):
         export to `<id>.md.tmp` → rename to `<id>.md`
         (export shape depends on row's `deleted_at`: full or tombstone)
     - **remote-only** (id we've never seen locally, file present on disk):
         parse → if `status == DELETED` skip (no point materializing a
         row only to tombstone it) → else insert via
         `repository.insertFromFile(envelope)`
     - **both, matching liveness** (both alive, or both tombstoned):
         skip — edits are not propagated, and matching tombstones are
         already in agreement.
     - **both, local alive + disk tombstoned**:
         honor the disk tombstone — soft-delete locally.
     - **both, local tombstoned + disk alive**:
         re-export the tombstone shape to overwrite the disk file.
5. UPDATE fs_sync_state.last_full_sync_at = now.
```

### Atomic write

Every file write goes through:

```
1. Build the bytes.
2. Create child <name>.tmp via DocumentFile.createFile(...).
3. Write bytes via ContentResolver.openOutputStream(tmpUri).
4. Rename via DocumentFile.renameTo("<name>") — POSIX-equivalent
   atomic rename on local file systems; SAF providers backed by
   Syncthing / Obsidian directories are local FS.
5. On any exception during write, delete the .tmp child to avoid
   half-written orphans.
```

Importers list-and-filter on `.md` suffix only, so `.tmp` files are
invisible to them.

### Tombstone GC

`FsTombstoneGcWorker` runs once per day:

- For each row with `deleted_at < now() - 30 days`:
  - Delete `<id>.md` from the SAF directory.
  - Delete any `attachments/<att_id>.<ext>` for that item.
  - Physically delete the row + its attachment rows from local DB.
- The 30-day window gives every connected device time to observe the
  tombstone before the file disappears entirely.

## Triggers and coordination

`FsSyncCoordinator.requestImmediateSync()` always goes through:

```kotlin
WorkManager.getInstance(ctx).enqueueUniqueWork(
    "fs_sync",
    ExistingWorkPolicy.KEEP,
    OneTimeWorkRequestBuilder<FsSyncWorker>().build(),
)
```

KEEP policy collapses concurrent triggers. Call sites:

- `MainActivity.onCreate` — once per app cold start, only if a folder
  URI is configured.
- `SummarizeWorker.success()` path — propagates a freshly-summarized
  item to disk within seconds (and pulls anything new off disk in the
  same run).
- Settings screen "今すぐ同期" button.
- The detail-screen delete cycle's `finalizeDelete` — so the
  tombstone is published immediately rather than waiting for the
  next periodic.

`PeriodicWorkRequest<FsSyncWorker>(intervalMinutes, flex 5 min)` is
enqueued (with `ExistingPeriodicWorkPolicy.UPDATE`) when the user
picks an interval. Default 30 minutes; user-selectable: 15, 30, 60,
360 minutes, or "manual-only" (cancels the periodic but keeps the
trigger-driven path).

## OAuth flow

None. Filesystem access is via SAF; no accounts, no tokens.

## UI

### Settings screen additions

A new section, "ローカル/外部 Markdown 同期":

- **Unconfigured**: "同期フォルダ未設定" + 「同期フォルダを選ぶ」 button. Tapping it launches `ACTION_OPEN_DOCUMENT_TREE`. The picked URI is persisted with `takePersistableUriPermission(... | READ | WRITE)` and written to `fs_sync_state` + `FsSyncFolderStore`. The first sync runs immediately on success.
- **Configured**:
  - Folder display name (from `DocumentFile.fromTreeUri(...).name` or a fallback rendering of the URI)
  - 「フォルダを変更」 / 「同期を解除」 buttons
  - Status row (アイドル / 同期中… / エラー: <msg>)
  - 「最終同期 yyyy-MM-dd HH:mm」 row when present
  - 同期間隔 dropdown (15 / 30 / 60 / 360 / 自動のみ)
  - 「今すぐ同期」 button (disabled while running)

State comes entirely from `FsSyncStateRepository.state: StateFlow<FsSyncState>`.

### No new screens

The folder picker is a system intent; no in-app picker UI.

## Error handling matrix

| Cause | Behaviour |
|---|---|
| No folder configured | `FsSyncWorker` returns `Result.success()` early; no UI noise. |
| SAF permission revoked (user cleared from system settings) | Catch `SecurityException` from `ContentResolver` operations; UI shows "再選択してください" and the folder picker becomes the only enabled action. |
| YAML parse failure on a `.md` file | Log a warning with file name + first 200 bytes of content, skip that file, continue the run. The file lives in user space — they may have renamed it, edited frontmatter manually, dropped a stray file, etc. |
| `.tmp` file from a crashed prior write found at start of run | Best-effort delete. If delete fails, leave it; it's invisible to importers. |
| Disk full / SAF write fails | Mark `FsSyncState.Error`, retry via WorkManager backoff. |
| Schema mismatch (file with `schema_version` we don't understand) | Skip the file with a logged warning. v1 has no schema_version field, so this is forward-looking. |

## Testing strategy

### Unit (`src/test/`)

- `MarkdownExporterTest`: round-trip an `InboxItem` with all field
  shapes (alive item with attachments, tombstoned item, item with
  null event, item with empty lists). Assert the produced Markdown
  text exactly.
- `MarkdownImporterTest`: parse the exporter's output back to an
  envelope; assert equality. Plus negative cases: missing `id` →
  typed parse error; malformed YAML → typed parse error; unknown
  fields → ignored.
- `FsSyncEngineDiffTest` (with fake DAO + fake folder access):
  - local-only alive → exports to disk
  - local-only tombstoned → exports as tombstone
  - remote-only alive → imports
  - remote-only tombstoned → no-op (we never had the id)
  - both alive → skip (no edit propagation)
  - local alive + disk tombstoned → soft-delete locally
  - local tombstoned + disk alive → re-export tombstone

### Instrumented (`src/androidTest/`)

- `FsSyncSafSmokeTest`: configures a temporary directory under
  `cacheDir` as a fake "SAF tree" via the `androidx.documentfile`
  test wrapper, runs the worker, asserts files materialize.
- `MainMigration2To3Test` (if a migration is added later — not for v1
  since we're going pre-release no-migration).

### Manual (`docs/superpowers/manual-tests/2026-05-04-fs-markdown-sync-manual.md`)

- Two-device script using Syncthing as the inter-device transport:
  pick the same Syncthing-watched directory on both devices, share
  on A, observe on B.
- Tombstone propagation.
- Re-pick directory after permission revocation.
- External edit (touch a `.md` in Obsidian, save) → next sync
  ignores the change because we don't propagate edits — confirm via
  diffing the file post-sync.

## Implementation phasing (rough — for the writing-plans pass)

1. Add `inbox_items.deleted_at` column + `fs_sync_state` table. No
   migration shipped (per project pre-release stance). Update DAO
   reads to filter `deleted_at IS NULL` for user-facing queries.
2. Tombstone-aware `InboxRepository.softDelete / restoreDeleted /
   finalizeDelete` (mirrors the work that landed and was reverted in
   the Drive sync attempt; structurally identical — see commit
   `cb923e6` in the closed PR #4 history if helpful).
3. `MarkdownExporter` + `MarkdownImporter` + their unit tests.
4. `SafFolderAccess` + `FsSyncFolderStore`.
5. `FsSyncEngine` (diff + apply) with fake-folder-access tests.
6. `FsSyncCoordinator` + `FsSyncWorker` + `FsSyncState` + `FsSyncStateRepository`.
7. Settings UI (folder picker, status, interval, sync now).
8. Trigger wiring (MainActivity start, SummarizeWorker chain,
   periodic, post-finalizeDelete).
9. `FsTombstoneGcWorker`.
10. Manual test doc + Syncthing-mediated two-device exercise.

## File structure (planned new files)

| File | Responsibility |
|---|---|
| `app/.../sync/MarkdownExporter.kt` | YAML frontmatter + body serialization |
| `app/.../sync/MarkdownImporter.kt` | YAML + body parsing → typed envelope |
| `app/.../sync/MarkdownEnvelope.kt` | Wire data class shared by exporter / importer |
| `app/.../sync/SafFolderAccess.kt` | DocumentFile wrapper (list / read / atomic write / rename / delete) |
| `app/.../sync/FsSyncFolderStore.kt` | EncryptedSharedPreferences-backed URI persistence |
| `app/.../sync/FsSyncEngine.kt` | Diff + apply core |
| `app/.../sync/FsSyncCoordinator.kt` | Trigger dedupe |
| `app/.../sync/FsSyncStateRepository.kt` | UI state SSOT |
| `app/.../sync/FsSyncState.kt` | Sealed UI state class |
| `app/.../work/FsSyncWorker.kt` | CoroutineWorker |
| `app/.../work/FsTombstoneGcWorker.kt` | Daily GC |
| `app/.../di/FsSyncModule.kt` | Hilt providers |
| `app/.../data/db/FsSyncStateEntity.kt` + `FsSyncStateDao.kt` | Persistent sync state row |
| `app/src/main/res/values/strings.xml` | Settings strings |
| Modifications: `InboxRepository`, `InboxDao`, `Migrations` (if a migration is shipped later), `AppDatabase`, `SettingsScreen`, `SettingsViewModel`, `SettingsUiState`, `MainActivity`, `SummarizeWorker`, `DetailViewModel` (post-finalize sync trigger) | (per-task) |

## Dependencies to add

- `org.jetbrains.kotlinx:kotlinx-serialization-yaml` for YAML
  frontmatter encoding/decoding (existing project already uses
  `kotlinx-serialization-json` and the `kotlin.plugin.serialization`
  plugin, so the code-gen is already set up).
- `androidx.documentfile:documentfile` for the `DocumentFile` API
  (small, official, stable).
