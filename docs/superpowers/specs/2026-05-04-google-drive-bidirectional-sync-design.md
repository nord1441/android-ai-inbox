# Google Drive bidirectional Inbox sync

## Goal

Let a single user keep the same AI Inbox content (items, summaries,
attachments, deletions) in lock-step across multiple Android devices by
syncing to the user's own Google Drive `appData` scope. New items
shared on one device must appear on the others within minutes; edits
and deletes must propagate without losing data; the experience must
degrade gracefully when offline.

## Non-goals

- End-to-end encryption. Drive's server-side encryption is the
  confidentiality boundary; user opted into trusting Google.
- Field-level merge or CRDT semantics. Last-Write-Wins per item is the
  conflict resolution model.
- Multi-account sync, sync to providers other than Drive, or sync of
  the on-device LLM model file (kept manual via `scripts/push-model.sh`).
- Differential transfer of attachment binaries (an attachment edit
  re-uploads the whole file).
- Push-style change notifications. We poll `manifest.json` via ETag
  rather than subscribing to Drive's `changes` API.
- Mandatory onboarding. Sync is opt-in; the app remains fully usable
  without ever linking a Drive account.

## Architecture

### Components

| Component | Responsibility |
|---|---|
| `DriveAuthRepository` | Account selection via Credential Manager, OAuth consent for the `drive.appdata` scope via Authorization API, persist + refresh tokens through `DriveTokenStore`, surface "needs reauth" state. |
| `DriveTokenStore` | Mirror of `HfTokenStore`: write access / refresh tokens through Android Keystore-backed `EncryptedSharedPreferences`. |
| `DriveApiClient` | OkHttp wrapper for the four Drive REST v3 endpoints we need (`files.create`, `files.update`, `files.delete`, `files.list`, `files.get?alt=media`). Adds `Authorization: Bearer` header, retries on 401 by triggering token refresh, supports `If-None-Match` ETag conditional GET. |
| `SyncManifest`, `SyncEnvelope` | `kotlinx.serialization` data classes for the wire format on Drive (`manifest.json` and `items/{id}.json`). |
| `SyncEngine` | Pure-ish core: given local DB state and a remote manifest, computes the diff (local-only / remote-only / both with conflict), applies pulls, applies pushes, emits the new manifest. |
| `SyncCoordinator` | Trigger aggregation + dedupe using `WorkManager.enqueueUniqueWork(name = "drive_sync", policy = KEEP)`. Single entry point that other components call. |
| `SyncWorker` (CoroutineWorker) | The WorkManager executor; obtains tokens, runs `SyncEngine`, updates `SyncStateRepository`. |
| `SyncStateRepository` | Single source of truth for sync UI state. Exposes `StateFlow<SyncState>` (idle / running / error / lastSyncedAt / accountEmail). |

### Boundaries and rationale

`SyncEngine` is deliberately split from `SyncWorker` so the algorithm
is testable in pure unit tests with a fake `DriveApiClient` and an
in-memory DAO; the worker only handles WorkManager plumbing,
foreground notification, and result mapping.

`DriveAuthRepository` and `DriveApiClient` are split because token
refresh policy (when to refresh, when to require reauth) is a separate
concern from REST call construction. The client takes a token
provider lambda rather than holding a token directly so unit tests can
inject a fixed token without touching the auth stack.

## Data model changes

### DB migration `MIGRATION_2_3`

```sql
ALTER TABLE inbox_items ADD COLUMN deleted_at INTEGER;
ALTER TABLE inbox_items ADD COLUMN last_synced_at INTEGER;
ALTER TABLE attachments ADD COLUMN deleted_at INTEGER;

CREATE TABLE sync_state (
    id INTEGER PRIMARY KEY CHECK(id = 1),
    account_email TEXT,
    last_full_sync_at INTEGER,
    last_manifest_etag TEXT
);

-- Initialize the singleton row so subsequent UPDATEs always have a target.
INSERT INTO sync_state (id, account_email, last_full_sync_at, last_manifest_etag)
VALUES (1, NULL, NULL, NULL);
```

### Behavioural change: hard delete → tombstone

`InboxRepository.delete(id)` currently calls `dao.delete()` (physical
DELETE) and `imageStore.delete()`. Change to:

1. Set `inbox_items.deleted_at = now()` (and the same on related
   `attachments` rows).
2. Delete the encrypted image binaries from
   `EncryptedImageStore` immediately (the file system bytes are local
   and wasteful to keep; the row's `deleted_at` is what propagates the
   delete intent to other devices via `manifest.json`).
3. All read queries in `InboxDao` gain `WHERE inbox_items.deleted_at IS NULL`.
4. The FTS5 trigger (`Migrations.kt`) is updated so deleting the
   logical row also deletes the FTS row.

A separate `TombstoneGcWorker` runs daily; it physically deletes
`inbox_items` rows whose `deleted_at < now() - 30 days` and emits a
`files.delete` for the corresponding Drive entries.

## Drive layout (`appData` scope)

The `appData` scope means the app gets a hidden, per-app folder that
no other app can read. It counts against the user's normal Drive
quota and survives reinstall (so onboarding on a re-installed device
fetches everything back).

```
appData/
├── manifest.json
├── items/
│   ├── {item_id}.json
│   └── ...
└── attachments/
    ├── {attachment_id}.bin
    └── ...
```

### `manifest.json`

```jsonc
{
  "schema_version": 1,
  "generated_at": 1746345600000,           // epoch ms, last writer's clock
  "items": [
    {
      "id": "f08528b4-...",
      "updated_at": 1746345500000,
      "deleted_at": null,                  // or epoch ms
      "attachment_ids": ["dbf1b467-..."],
      "attachment_byte_sizes": [482311]    // for early local "no change" check
    }
  ]
}
```

The manifest is the single source of "what exists remotely" — every
sync starts by GETting it (with `If-None-Match: <last_etag>`); a 304
short-circuits to "remote unchanged, just push local changes".

### `items/{id}.json`

The full row payload from `inbox_items` plus the embedded
`ExtractedEvent`. List columns (`tags`, `people`, `places`, `urls`)
are JSON arrays. `user_edited_fields` is a JSON array. No attachment
binary lives here — only `attachment_ids` cross-references.

### `attachments/{att_id}.bin`

The decrypted image bytes (Drive's server-side encryption is the
confidentiality boundary). Filename uses the local DB
`attachments.id`. No metadata header — the corresponding row in the
local `attachments` table holds `mime_type`, `byte_size`, etc.

## Sync algorithm

A single sync run:

```
1. Read sync_state.last_manifest_etag.
2. Drive GET manifest.json with If-None-Match.
   - 304 → no remote changes; jump to step 6 with empty pull set.
   - 200 → parse new manifest, capture new ETag.
3. Compute the diff:
     local  = SELECT id, updated_at, deleted_at, last_synced_at FROM inbox_items
     remote = parsed manifest items
   For each id in local ∪ remote:
     - local-only AND (last_synced_at IS NULL OR local more recent than last sync)
         → push candidate
     - remote-only
         → pull candidate
     - both:
         localTs  = max(local.updated_at, local.deleted_at ?: 0)
         remoteTs = max(remote.updated_at, remote.deleted_at ?: 0)
         if remoteTs > localTs → pull
         if localTs  > remoteTs → push
         if equal → skip (already in sync)
4. For each pull candidate (in id order, deterministic):
     - GET items/{id}.json, upsert into inbox_items.
     - For each attachment_id new to local:
         GET attachments/{att_id}.bin, write through EncryptedImageStore,
         insert/update attachments row.
     - For attachments removed in remote: tombstone locally.
5. For each push candidate (in id order):
     - PUT items/{id}.json (create if absent, update by file id otherwise).
     - For each new attachment: upload attachments/{att_id}.bin.
     - For tombstoned items: PUT items/{id}.json with deleted_at set
       (we do not Drive-DELETE until GC; the manifest carries the
       tombstone so other devices observe it).
6. Build new manifest reflecting post-sync state, PUT manifest.json.
   Capture the response ETag → sync_state.last_manifest_etag.
7. UPDATE inbox_items SET last_synced_at = now() for items touched.
8. UPDATE sync_state.last_full_sync_at = now().
```

### Conflict resolution

LWW per item. The "timestamp" is `max(updated_at, deleted_at ?: 0)`.
Ties (extremely rare) resolve to the remote value to keep the manifest
authoritative.

### Tombstone GC

`TombstoneGcWorker` runs once per day:

- For each row with `deleted_at < now() - 30 days`:
  - Drive `DELETE` on `items/{id}.json` and each `attachments/{att_id}.bin`.
  - Local DELETE the row.
- Then push a fresh manifest without the GC'd entries.

## Triggers and coordination

`SyncCoordinator.requestSync()` always goes through:

```kotlin
WorkManager.getInstance(ctx).enqueueUniqueWork(
    "drive_sync",
    ExistingWorkPolicy.KEEP,
    OneTimeWorkRequestBuilder<SyncWorker>().build(),
)
```

`KEEP` policy ensures concurrent triggers collapse to a single
in-flight run. Call sites:

- `MainActivity.onCreate` — once per app cold start.
- `SummarizeWorker.success()` path — chained via `WorkManager.beginUniqueWork(...).then(SyncWorkRequest)` so a freshly summarized item propagates immediately.
- Settings screen "今すぐ同期" button.

A separate `PeriodicWorkRequest<SyncWorker>` is enqueued (with
`ExistingPeriodicWorkPolicy.UPDATE`) when the user enables sync.
Default interval 30 minutes, flex window 5 minutes. User-selectable
intervals: 15 min, 30 min, 1 hour, 6 hours, "auto-only" (= disable
periodic but keep the trigger-driven path).

## OAuth flow

1. User taps **「Drive をリンク」** in Settings.
2. `DriveAuthRepository.startLink(activity)`:
   a. `CredentialManager.getCredential()` with `GetSignInWithGoogleOption` → returns the Google ID token + email.
   b. `Identity.getAuthorizationClient(context).authorize(AuthorizationRequest)` requesting scope `https://www.googleapis.com/auth/drive.appdata`. Granted scopes appear in the result.
   c. Persist the resulting `accessToken`, `refreshToken`, expiry, and account email via `DriveTokenStore`.
   d. Update `sync_state.account_email`.
3. On every API call, `DriveApiClient` reads the access token from
   `DriveTokenStore`. On 401, it calls `DriveAuthRepository.refresh()`
   once and retries; second 401 = mark `SyncState = ReauthRequired`
   and surface to the UI.
4. Unlink button clears `DriveTokenStore` and `sync_state.account_email`,
   leaves local DB intact (data is not deleted on unlink).

## UI

### Settings screen additions

A new section "Google Drive 同期":

- Status row:
  - Unlinked: "未リンク" + 「リンクする」 button.
  - Linked: account email + 「リンク解除」 button.
  - Running: spinner + "同期中…".
  - Error: red text with one-line cause + 「再リンク」 / 「再試行」 button as appropriate.
  - Last sync: "最終同期 yyyy-MM-dd HH:mm".
- Sync interval `DropdownMenu`: 15 / 30 / 60 / 360 minutes / 自動のみ.
- 「今すぐ同期」 Button (disabled while running).

State comes entirely from `SyncStateRepository.state: StateFlow<SyncState>`.

### No new screens

The link button drives the system OAuth dialog (Credential Manager
sheet → Authorization consent), so no in-app UI for picking accounts.
Initial full sync runs as a background `SyncWorker` invocation; the
status row reflects "同期中" until done. There is no separate progress
modal — the existing FGS notification covers cross-app visibility.

## Error handling matrix

| Cause | Behaviour |
|---|---|
| Token expired | `DriveApiClient` triggers refresh once; if refresh fails, `SyncState = ReauthRequired`; UI shows reauth button. |
| No network | `SyncWorker` returns `Result.retry()`; WorkManager retries with default exponential backoff. UI shows `error = NetworkUnavailable`. |
| Drive quota exceeded (`storageQuotaExceeded`) | Stop retrying (backoff cannot recover). UI shows "Drive の容量不足" with link to drive.google.com/settings/storage. Auto-recover on next manual or periodic trigger after the user frees space. |
| Drive 5xx | `Result.retry()` (backoff). |
| ETag conflict on manifest PUT (412) | Drop our manifest, restart sync from step 2 (re-pull manifest, recompute diff). At most 2 retries per run. |
| Local DB error | Abort run with `Result.failure()`, log via `android.util.Log.e`. |
| Item conflict | Resolved silently by LWW; not reported as an error. |
| Account lost (user revoked at accounts.google.com) | Same as token expired → reauth required. |

## Testing strategy

### Unit (`src/test/`)

- `SyncEngineDiffTest`: covers six scenarios — local-only, remote-only, local-newer, remote-newer, local-deleted, remote-deleted, plus the equal-timestamp tie case.
- `SyncEngineLwwTest`: prove `max(updated_at, deleted_at ?: 0)` semantics for the four combinations of (deleted/alive) × (local/remote).
- `SyncManifestSerializationTest`: round-trip `manifest.json` and `items/{id}.json` through `Json.encodeToString` / `decodeFromString`; assert `schema_version` is preserved.
- `DriveApiClientTest`: MockWebServer fixture asserts request shape (path, method, headers including `Authorization: Bearer …` and `If-None-Match`) for each endpoint.
- `DriveAuthRepositoryTest`: with a fake `DriveTokenStore`, verify refresh-once-then-fail flow ends in `ReauthRequired`.

### Instrumented (`src/androidTest/`)

- `Migration2To3Test`: open a v2 database with sample rows, run `MIGRATION_2_3`, assert new columns exist with NULL values, `sync_state` has the singleton row, and Room can open it normally.
- `InboxRepositoryDeleteTombstoneTest`: call `delete(id)`; assert row is still in the DB but `deleted_at` is set, the encrypted image file is gone, and `observeAll()` does not emit it.
- `SyncWorkerSmokeTest`: with a stubbed `DriveApiClient` returning a fixed manifest, run `SyncWorker.doWork()` end-to-end and assert local DB matches.

### Manual (`docs/superpowers/manual-tests/2026-05-04-drive-sync-manual.md`)

- Two-device script: link both, share content on device A, observe within 30 s on device B.
- Delete on A, sync on B, observe disappearance.
- Disable Wi-Fi on A, share, re-enable, sync, observe.
- Revoke OAuth grant at myaccount.google.com, attempt sync, observe reauth UI.
- Fill Drive quota (use a dummy account near full), attempt sync, observe quota error UI.

## Implementation phasing (rough — for the writing-plans pass)

1. DB migration + tombstone behaviour change + DAO query updates + tests.
2. `DriveTokenStore` + `DriveAuthRepository` + Settings UI for link/unlink (no actual sync yet).
3. `DriveApiClient` + `SyncManifest`/`SyncEnvelope` + their tests.
4. `SyncEngine` (pure logic) + tests.
5. `SyncWorker` + `SyncCoordinator` + Settings UI for "今すぐ同期" / interval / status.
6. Trigger wiring (MainActivity start, SummarizeWorker chain, periodic).
7. `TombstoneGcWorker`.
8. Manual test doc + cross-device exercise.

Each phase produces working, testable software; phases 1-2 alone
already give the user "linking" without mutation, useful for staging.

## File structure (planned new files)

| File | Responsibility |
|---|---|
| `app/.../sync/DriveAuthRepository.kt` | OAuth coordination |
| `app/.../sync/DriveTokenStore.kt` | Token persistence (Keystore) |
| `app/.../sync/DriveApiClient.kt` | REST wrapper |
| `app/.../sync/SyncManifest.kt` | Wire format data classes |
| `app/.../sync/SyncEnvelope.kt` | item / attachment payload |
| `app/.../sync/SyncEngine.kt` | Diff + apply core |
| `app/.../sync/SyncCoordinator.kt` | Trigger dedupe |
| `app/.../sync/SyncStateRepository.kt` | UI state SSOT |
| `app/.../sync/SyncState.kt` | sealed UI state class |
| `app/.../work/SyncWorker.kt` | CoroutineWorker |
| `app/.../work/TombstoneGcWorker.kt` | Daily GC |
| `app/.../di/SyncModule.kt` | Hilt providers for sync subsystem |
| `app/src/main/res/values/strings.xml` | Settings strings (linked / unlinked / interval labels / errors) |
| Modifications: `InboxRepository`, `InboxDao`, `Migrations`, `AppDatabase`, `SettingsScreen`, `SettingsViewModel`, `SettingsUiState`, `MainActivity` |

(Plan task decomposition will assign exact file lists per task.)
