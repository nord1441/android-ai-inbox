# Filesystem + Markdown Inbox sync — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Export each inbox item as a plain Markdown file (YAML frontmatter + body) into a SAF-picked directory, and import any unseen-id `.md` the directory grows. Movement of files between devices is delegated to whatever tool watches that directory; this app never speaks a network protocol.

**Architecture:** Pure-function exporter / importer pair shared by a `FsSyncEngine` that diffs by id (presence-on-disk vs presence-in-DB), then routes per-id to export / import / soft-delete / re-export-tombstone. WorkManager-driven `FsSyncWorker` triggered on app start, after every summarize, after every finalize-delete, on a configurable periodic schedule, and on a manual button.

**Tech Stack:** Kotlin, Jetpack Compose, Room (with SQLCipher), kotlinx.serialization (JSON + YAML), `androidx.documentfile`, WorkManager, Hilt.

**Spec:** `docs/superpowers/specs/2026-05-04-fs-markdown-sync-design.md` — refer to it for design rationale; this plan only restates exact code/SQL where needed for execution.

---

## Pre-flight: dependencies

Add two new Gradle deps in **Task 0**.

In `gradle/libs.versions.toml`:

```toml
[versions]
kotlinxSerializationYaml = "0.0.18"
documentfile = "1.0.1"

[libraries]
kotlinx-serialization-yaml = { module = "com.charleskorn.kaml:kaml", version.ref = "kotlinxSerializationYaml" }
androidx-documentfile = { module = "androidx.documentfile:documentfile", version.ref = "documentfile" }
```

(We use `com.charleskorn.kaml:kaml` rather than `kotlinx-serialization-yaml` because the latter does not exist as a stable artifact; `kaml` is the de-facto kotlinx.serialization YAML format. Despite the lib name `kotlinxSerializationYaml` in the version key, the artifact ID is the one that gets resolved.)

In `app/build.gradle.kts`:

```kotlin
implementation(libs.kotlinx.serialization.yaml)
implementation(libs.androidx.documentfile)
```

---

## File Structure

| File | Responsibility | Created in |
|---|---|---|
| `app/src/main/kotlin/.../sync/MarkdownEnvelope.kt` | Wire data class shared by exporter / importer (`@Serializable`) | Task 3 |
| `app/src/main/kotlin/.../sync/MarkdownExporter.kt` | DB row + attachments → bytes (frontmatter + body); pure | Task 3 |
| `app/src/main/kotlin/.../sync/MarkdownImporter.kt` | bytes → envelope; pure; typed parse errors | Task 4 |
| `app/src/main/kotlin/.../sync/SafFolderAccess.kt` | DocumentFile wrapper (atomic write, list, read, delete) | Task 5 |
| `app/src/main/kotlin/.../sync/FsSyncFolderStore.kt` | EncryptedSharedPreferences-backed URI persistence | Task 5 |
| `app/src/main/kotlin/.../sync/FsSyncEngine.kt` | Diff + apply core | Tasks 6 + 7 |
| `app/src/main/kotlin/.../sync/FsSyncCoordinator.kt` | Trigger dedupe via WorkManager unique work | Task 8 |
| `app/src/main/kotlin/.../sync/FsSyncStateRepository.kt` | UI state SSOT | Task 8 |
| `app/src/main/kotlin/.../sync/FsSyncState.kt` | Sealed UI state class | Task 8 |
| `app/src/main/kotlin/.../work/FsSyncWorker.kt` | CoroutineWorker that drives one sync run | Task 8 |
| `app/src/main/kotlin/.../work/FsTombstoneGcWorker.kt` | Daily GC of >30d tombstones | Task 11 |
| `app/src/main/kotlin/.../di/FsSyncModule.kt` | Hilt providers for sync subsystem | Task 8 |
| `app/src/main/kotlin/.../data/db/FsSyncStateEntity.kt` + `FsSyncStateDao.kt` | Persistent sync state row | Task 1 |
| `app/src/test/kotlin/.../sync/MarkdownExporterTest.kt` | Round-trip unit tests | Task 3 |
| `app/src/test/kotlin/.../sync/MarkdownImporterTest.kt` | Parse + negative-case tests | Task 4 |
| `app/src/test/kotlin/.../sync/FsSyncEngineDiffTest.kt` | 5-state-pair diff tests | Task 6 |
| `app/src/androidTest/kotlin/.../sync/FsSyncSafSmokeTest.kt` | End-to-end with a temp directory acting as SAF tree | Task 10 |
| `docs/superpowers/manual-tests/2026-05-04-fs-markdown-sync-manual.md` | Two-device exercise via Syncthing | Task 12 |
| Modifications: `InboxItem`, `InboxDao`, `AttachmentDao`, `AppDatabase`, `SqlCipherFactory`, `InboxRepository`, `SettingsScreen`, `SettingsViewModel`, `SettingsUiState`, `MainActivity`, `WorkScheduler`, `SummarizeWorker`, `DetailViewModel` | (per-task) | Various |

---

## Task 0: Add Gradle dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add version + library entries**

In `gradle/libs.versions.toml`, append into the existing `[versions]` block (alphabetical order):

```toml
documentfile = "1.0.1"
kotlinxSerializationYaml = "0.0.18"
```

And into the `[libraries]` block (alphabetical order):

```toml
androidx-documentfile = { module = "androidx.documentfile:documentfile", version.ref = "documentfile" }
kotlinx-serialization-yaml = { module = "com.charleskorn.kaml:kaml", version.ref = "kotlinxSerializationYaml" }
```

- [ ] **Step 2: Add `implementation` lines**

In `app/build.gradle.kts`, inside the `dependencies { ... }` block, add (near the other `implementation(libs.*)` lines):

```kotlin
implementation(libs.androidx.documentfile)
implementation(libs.kotlinx.serialization.yaml)
```

- [ ] **Step 3: Sync + compile**

```
./gradlew --no-daemon :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:compileDebugUnitTestKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
GIT_AUTHOR_NAME='nord14541' GIT_AUTHOR_EMAIL='nord14541@gmail.com' \
GIT_COMMITTER_NAME='nord14541' GIT_COMMITTER_EMAIL='nord14541@gmail.com' \
  git commit -m "build(sync): add documentfile + kaml YAML deps"
```

---

## Task 1: DB additions (`inbox_items.deleted_at`, `fs_sync_state`)

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/InboxItem.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/AppDatabase.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/data/db/FsSyncStateEntity.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/data/db/FsSyncStateDao.kt`

- [ ] **Step 1: Add `deleted_at` to `InboxItem`**

In `InboxItem.kt`, inside the `data class InboxItem(...)` constructor, add (after `updatedAt`):

```kotlin
@ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
```

- [ ] **Step 2: Create `FsSyncStateEntity`**

```kotlin
package com.example.aiinbox.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding cross-cutting filesystem-sync state. Persisted
 * in addition to FsSyncFolderStore (EncryptedSharedPreferences) so the
 * sync engine can read it without a Context, and so a future export can
 * include the URI in support bundles if needed.
 */
@Entity(tableName = "fs_sync_state")
data class FsSyncStateEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "folder_uri") val folderUri: String? = null,
    @ColumnInfo(name = "last_full_sync_at") val lastFullSyncAt: Long? = null,
)
```

- [ ] **Step 3: Create `FsSyncStateDao`**

```kotlin
package com.example.aiinbox.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FsSyncStateDao {
    @Query("SELECT * FROM fs_sync_state WHERE id = 1 LIMIT 1")
    suspend fun get(): FsSyncStateEntity?

    @Query("SELECT * FROM fs_sync_state WHERE id = 1 LIMIT 1")
    fun observe(): Flow<FsSyncStateEntity?>

    @Upsert
    suspend fun upsert(state: FsSyncStateEntity)
}
```

- [ ] **Step 4: Wire AppDatabase**

In `AppDatabase.kt`:

- Bump `version = 3`.
- Add `FsSyncStateEntity::class` to the `entities = [...]` list.
- Add `abstract fun fsSyncStateDao(): FsSyncStateDao`.

- [ ] **Step 5: Provide the DAO via Hilt**

In `app/src/main/kotlin/com/example/aiinbox/di/DatabaseModule.kt`, add the import and the provider:

```kotlin
import com.example.aiinbox.data.db.FsSyncStateDao
// ...inside the object:
@Provides
fun provideFsSyncStateDao(db: AppDatabase): FsSyncStateDao = db.fsSyncStateDao()
```

- [ ] **Step 6: Export new schema**

```
./gradlew --no-daemon :app:kspDebugKotlin
```

Expected: `app/schemas/com.example.aiinbox.data.db.AppDatabase/3.json` is created. Stage it.

- [ ] **Step 7: Compile**

```
./gradlew --no-daemon :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/db/InboxItem.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/AppDatabase.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/FsSyncStateEntity.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/FsSyncStateDao.kt \
        app/src/main/kotlin/com/example/aiinbox/di/DatabaseModule.kt \
        app/schemas/com.example.aiinbox.data.db.AppDatabase/3.json
GIT_AUTHOR_NAME='nord14541' GIT_AUTHOR_EMAIL='nord14541@gmail.com' \
GIT_COMMITTER_NAME='nord14541' GIT_COMMITTER_EMAIL='nord14541@gmail.com' \
  git commit -m "feat(db): add inbox_items.deleted_at + fs_sync_state for FS sync"
```

---

## Task 2: Tombstone-aware delete + DAO read-query audit

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/InboxDao.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/AttachmentDao.kt` (only if attachments need deleted-at filtering — they currently do not because the new column is on `inbox_items` only, and the existing `attachments` table has no `deleted_at`. CASCADE keeps the attachments deletion in sync with hard deletes.)
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/repository/InboxRepository.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/FtsCallback.kt`

- [ ] **Step 1: Add tombstone setter + restore + including-deleted lookups to `InboxDao`**

Append to `InboxDao.kt`:

```kotlin
@Query("UPDATE inbox_items SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE id = :id")
suspend fun markDeleted(id: String, deletedAt: Long)

@Query("UPDATE inbox_items SET deleted_at = NULL, updated_at = :restoredAt WHERE id = :id")
suspend fun restore(id: String, restoredAt: Long)

/** Sync / GC point lookup including tombstoned rows. */
@Transaction
@Query("SELECT * FROM inbox_items WHERE id = :id LIMIT 1")
suspend fun getWithAttachmentsIncludingDeleted(id: String): InboxItemWithAttachments?

/** Sync scan: every id with the metadata the engine needs, including tombstones. */
@Query("SELECT id, deleted_at AS deletedAt, updated_at AS updatedAt FROM inbox_items")
suspend fun allRefsIncludingDeleted(): List<InboxRefRow>

/** GC: tombstone rows whose deleted_at is older than [cutoff]. */
@Query("SELECT * FROM inbox_items WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
suspend fun tombstonesOlderThan(cutoff: Long): List<InboxItem>

/** GC: physically remove the row (after the tombstone window has closed). */
@Query("DELETE FROM inbox_items WHERE id = :id")
suspend fun physicalDeleteById(id: String)

@Upsert
suspend fun upsert(item: InboxItem)
```

Also add the `Upsert` import:

```kotlin
import androidx.room.Upsert
```

- [ ] **Step 2: Add the supporting `InboxRefRow` row carrier**

At file scope below the `InboxDao` interface in `InboxDao.kt`:

```kotlin
data class InboxRefRow(
    val id: String,
    val deletedAt: Long?,
    val updatedAt: Long,
)
```

- [ ] **Step 3: Audit every read query in `InboxDao` and add `WHERE inbox_items.deleted_at IS NULL`**

Walk every `@Query` annotation and every RawQuery SQL string in `InboxDao.kt`. For every SELECT that returns rows back to the UI / repository (observeAll, observeFiltered, observeSearch, observeSearchLike, observeAllWithAttachments, observeFilteredWithAttachments, observeSearchWithAttachments, observeSearchLikeWithAttachments, getByStatus, the FTS-joined searchFts, observeById, observeByIdWithAttachments, getByIdWithAttachments), add `inbox_items.deleted_at IS NULL` (or `i.deleted_at IS NULL` when the FROM has aliases) to the WHERE clause.

`getById` (the suspend point lookup) is **deliberately not filtered** — it's used by sync / GC / tests that need to see tombstoned rows.

For each modified query, mentally trace: "if this returned a tombstoned row to the UI, what would the user see?" If the answer is "the deleted item resurrected in the inbox list," your filter is needed.

- [ ] **Step 4: Add the same predicate to FTS triggers in `FtsCallback`**

In `FtsCallback.kt`, modify `inbox_items_ai` (INSERT trigger) and `inbox_items_au` (UPDATE trigger): convert the `INSERT INTO inbox_fts ... VALUES (...)` form to `INSERT INTO inbox_fts ... SELECT ... WHERE NEW.deleted_at IS NULL` so tombstoned rows never enter the FTS index. Keep `inbox_items_ad` (DELETE trigger) unchanged — physical deletes during GC should remove the FTS row.

For all three `attachments_*` triggers (INSERT / UPDATE / DELETE), change `WHERE i.id = NEW.item_id` (or `OLD.item_id` for the delete form) to `WHERE i.id = NEW.item_id AND i.deleted_at IS NULL` so attachment-side mutations on a tombstoned parent don't resurrect its FTS row.

- [ ] **Step 5: Replace `Repository.softDelete / restoreDeleted / finalizeDelete` with tombstone semantics**

In `InboxRepository.kt`, locate the existing block (approx. lines 165–190 starting with the `deletedBuffer` declaration and ending with the `delete(id)` function). Replace it with:

```kotlin
/**
 * Tombstone the item so that:
 * (a) it disappears from every UI / search query (which all filter
 *     `deleted_at IS NULL`),
 * (b) FS sync sees `deleted_at` set on the next sync run and rewrites
 *     the corresponding .md as a tombstone for other devices.
 *
 * Encrypted attachment files are kept on disk so [restoreDeleted] can
 * undo the operation; [finalizeDelete] purges them after the undo
 * window closes.
 */
suspend fun softDelete(id: String): Boolean {
    val current = dao.getById(id) ?: return false
    if (current.deletedAt != null) return false
    val now = System.currentTimeMillis()
    dao.markDeleted(id, now)
    return true
}

suspend fun restoreDeleted(id: String): Boolean {
    val full = dao.getWithAttachmentsIncludingDeleted(id) ?: return false
    if (full.item.deletedAt == null) return false
    dao.restore(id, System.currentTimeMillis())
    return true
}

suspend fun finalizeDelete(id: String) {
    val full = dao.getWithAttachmentsIncludingDeleted(id) ?: return
    full.attachments.forEach { imageStore.delete(it.encryptedFilename) }
    // The DB row stays as a tombstone until FsTombstoneGcWorker fires.
}
```

Remove the `private val deletedBuffer = ConcurrentHashMap<...>()` line and the unused `import java.util.concurrent.ConcurrentHashMap` if present.

- [ ] **Step 6: Add the sync helpers to `InboxRepository`**

Append to `InboxRepository.kt`:

```kotlin
/**
 * Every local item id with its (deleted_at, updated_at) pair, including
 * tombstones. FS sync compares this against the .md files on disk to
 * compute export / import / no-op per id.
 */
suspend fun allLocalRefs(): List<com.example.aiinbox.data.db.InboxRefRow> =
    dao.allRefsIncludingDeleted()

suspend fun getWithAttachmentsIncludingDeleted(id: String): com.example.aiinbox.data.db.InboxItemWithAttachments? =
    dao.getWithAttachmentsIncludingDeleted(id)

/**
 * Insert a fresh row from a Markdown envelope (FS sync import path).
 * Idempotent against the id; uses Room @Upsert so re-imports overwrite.
 */
suspend fun insertFromFile(item: com.example.aiinbox.data.db.InboxItem, attachments: List<com.example.aiinbox.data.db.Attachment>) {
    dao.upsert(item)
    if (attachments.isNotEmpty()) attachmentDao.insertAll(attachments)
}
```

- [ ] **Step 7: Compile**

```
./gradlew --no-daemon :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/db/InboxDao.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/FtsCallback.kt \
        app/src/main/kotlin/com/example/aiinbox/data/repository/InboxRepository.kt
GIT_AUTHOR_NAME='nord14541' GIT_AUTHOR_EMAIL='nord14541@gmail.com' \
GIT_COMMITTER_NAME='nord14541' GIT_COMMITTER_EMAIL='nord14541@gmail.com' \
  git commit -m "feat(repository): tombstone-aware delete + sync helpers; queries filter deleted_at IS NULL"
```

---

## Task 3: `MarkdownEnvelope` + `MarkdownExporter` + tests

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/MarkdownEnvelope.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/MarkdownExporter.kt`
- Create: `app/src/test/kotlin/com/example/aiinbox/sync/MarkdownExporterTest.kt`

- [ ] **Step 1: Define `MarkdownEnvelope`**

```kotlin
package com.example.aiinbox.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Round-trip representation of an inbox row + its attachments as it appears
 * inside a `.md` file's YAML frontmatter. The Markdown body holds only
 * `summary`; everything else lives here.
 *
 * Tombstoned rows are encoded with `status = "DELETED"` and most fields
 * left null — the importer's first job is to look at status before
 * trying to parse the rest.
 */
@Serializable
data class MarkdownEnvelope(
    val id: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val status: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    val title: String? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
    val event: EnvelopeEvent? = null,
    @SerialName("source_app") val sourceApp: String? = null,
    val attachments: List<EnvelopeAttachment> = emptyList(),
) {
    @Serializable
    data class EnvelopeEvent(
        val title: String,
        val start: String? = null,
        val end: String? = null,
        val location: String? = null,
        val confidence: Float = 0f,
    )

    @Serializable
    data class EnvelopeAttachment(
        val id: String,
        val file: String,
        val mime: String,
        @SerialName("width_px") val widthPx: Int = 0,
        @SerialName("height_px") val heightPx: Int = 0,
        @SerialName("byte_size") val byteSize: Long = 0,
        @SerialName("ocr_text") val ocrText: String? = null,
    )
}
```

- [ ] **Step 2: Implement `MarkdownExporter`**

```kotlin
package com.example.aiinbox.sync

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.example.aiinbox.data.db.Attachment
import com.example.aiinbox.data.db.InboxItem
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DB row → Markdown bytes. Pure: no I/O, no Android dependencies, fully
 * unit-testable. The shape of the produced bytes (frontmatter + body) is
 * specified in docs/superpowers/specs/2026-05-04-fs-markdown-sync-design.md.
 */
@Singleton
class MarkdownExporter @Inject constructor(
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val yaml = Yaml(
        configuration = YamlConfiguration(
            encodeDefaults = true,
            // Use block-style sequences and nested mappings (the human-readable
            // shape Obsidian and friends render natively).
            sequenceStyle = com.charleskorn.kaml.SequenceStyle.Block,
        )
    )

    fun encode(item: InboxItem, attachments: List<Attachment>): ByteArray {
        val envelope = if (item.deletedAt != null) {
            tombstoneEnvelope(item)
        } else {
            fullEnvelope(item, attachments)
        }
        val front = yaml.encodeToString(MarkdownEnvelope.serializer(), envelope)
        val body = if (item.deletedAt != null) "" else (item.summary.orEmpty())
        val text = buildString {
            append("---\n")
            append(front)
            if (!front.endsWith("\n")) append('\n')
            append("---\n\n")
            append(body)
            if (body.isNotEmpty() && !body.endsWith("\n")) append('\n')
        }
        return text.toByteArray(Charsets.UTF_8)
    }

    /** Filename a writer should use for [item]: `YYYY-MM-DD-<id>.md`. */
    fun filenameFor(item: InboxItem): String {
        val date = Instant.ofEpochMilli(item.receivedAt).atZone(zone).toLocalDate().toString()
        return "$date-${item.id}.md"
    }

    /** Filename a writer should use for an attachment binary. */
    fun attachmentFilename(att: Attachment): String {
        val ext = mimeToExt(att.mimeType)
        return "${att.id}.$ext"
    }

    private fun tombstoneEnvelope(item: InboxItem) = MarkdownEnvelope(
        id = item.id,
        receivedAt = formatInstant(item.receivedAt),
        updatedAt = formatInstant(item.updatedAt),
        status = "DELETED",
        deletedAt = item.deletedAt?.let(::formatInstant),
    )

    private fun fullEnvelope(item: InboxItem, attachments: List<Attachment>) = MarkdownEnvelope(
        id = item.id,
        receivedAt = formatInstant(item.receivedAt),
        updatedAt = formatInstant(item.updatedAt),
        status = item.status.name,
        deletedAt = null,
        title = item.title,
        category = item.category,
        tags = item.tags,
        people = item.people,
        places = item.places,
        urls = item.urls,
        event = item.event?.let {
            MarkdownEnvelope.EnvelopeEvent(
                title = it.title,
                start = it.startMillis?.let(::formatInstant),
                end = it.endMillis?.let(::formatInstant),
                location = it.location,
                confidence = it.confidence,
            )
        },
        sourceApp = item.sourceApp,
        attachments = attachments.map {
            MarkdownEnvelope.EnvelopeAttachment(
                id = it.id,
                file = "attachments/${attachmentFilename(it)}",
                mime = it.mimeType,
                widthPx = it.widthPx,
                heightPx = it.heightPx,
                byteSize = it.byteSize,
                ocrText = it.ocrText,
            )
        },
    )

    private fun formatInstant(epochMs: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).toString()

    private fun mimeToExt(mime: String): String = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "bin"
    }
}
```

- [ ] **Step 3: Write the round-trip tests**

`app/src/test/kotlin/com/example/aiinbox/sync/MarkdownExporterTest.kt`:

```kotlin
package com.example.aiinbox.sync

import com.example.aiinbox.data.db.Attachment
import com.example.aiinbox.data.db.AttachmentKind
import com.example.aiinbox.data.db.ExtractedEvent
import com.example.aiinbox.data.db.InboxItem
import com.example.aiinbox.data.db.ItemStatus
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MarkdownExporterTest {

    private val exporter = MarkdownExporter(zone = ZoneId.of("Asia/Tokyo"))

    private fun item(
        id: String = "abc",
        receivedAt: Long = 1746345334000L,  // 2026-05-04T12:35:34+09:00
        deletedAt: Long? = null,
        title: String? = "title",
        summary: String? = "the summary body",
    ) = InboxItem(
        id = id,
        originalText = null,
        originalSubject = null,
        sourceApp = "test",
        receivedAt = receivedAt,
        status = if (deletedAt == null) ItemStatus.COMPLETED else ItemStatus.COMPLETED,
        title = title,
        summary = summary,
        category = "買い物",
        tags = listOf("a", "b"),
        people = listOf("alice"),
        places = listOf("shop"),
        urls = listOf("https://example.com"),
        event = ExtractedEvent(title = "meet", startMillis = 1746400000000L, endMillis = null, location = "office", confidence = 0.9f),
        userEditedFields = emptySet(),
        updatedAt = receivedAt,
        deletedAt = deletedAt,
    )

    private fun attachment(id: String = "att-1") = Attachment(
        id = id,
        itemId = "abc",
        ordering = 0,
        kind = AttachmentKind.SHARED_IMAGE,
        encryptedFilename = "enc-1.jpg.enc",
        mimeType = "image/jpeg",
        widthPx = 800,
        heightPx = 600,
        byteSize = 12345L,
        ocrText = "scanned text",
        ocrCompletedAt = 1746345400000L,
        createdAt = 1746345334000L,
    )

    @Test
    fun encode_aliveItem_includesFrontmatterAndBody() {
        val bytes = exporter.encode(item(), listOf(attachment()))
        val text = bytes.toString(Charsets.UTF_8)

        assertTrue("must start with frontmatter delimiter", text.startsWith("---\n"))
        assertTrue("must contain second delimiter", text.contains("\n---\n"))
        assertTrue("must contain id", text.contains("id: \"abc\"") || text.contains("id: abc"))
        assertTrue("must contain title", text.contains("title:"))
        assertTrue("must contain attachments", text.contains("attachments:"))
        assertTrue("must contain attachment file path", text.contains("attachments/att-1.jpg"))
        assertTrue("body must include summary", text.endsWith("the summary body\n"))
    }

    @Test
    fun encode_tombstone_dropsBodyAndMostFields() {
        val bytes = exporter.encode(item(deletedAt = 1746345400000L, title = "to be hidden", summary = "secret"), emptyList())
        val text = bytes.toString(Charsets.UTF_8)

        assertTrue("must include status DELETED", text.contains("status: DELETED") || text.contains("status: \"DELETED\""))
        assertTrue("must not leak title", !text.contains("to be hidden"))
        assertTrue("must not leak summary", !text.contains("secret"))
        assertTrue("must include deleted_at", text.contains("deleted_at:"))
    }

    @Test
    fun filenameFor_usesReceivedAtDateInZone() {
        val name = exporter.filenameFor(item(id = "xyz", receivedAt = 1746345334000L))
        assertTrue("filename must start with date prefix in Asia/Tokyo", name.startsWith("2026-05-04-xyz"))
        assertTrue("must end with .md", name.endsWith(".md"))
    }

    @Test
    fun attachmentFilename_mapsMimeToExt() {
        val jpg = exporter.attachmentFilename(attachment(id = "a").copy(mimeType = "image/jpeg"))
        val png = exporter.attachmentFilename(attachment(id = "b").copy(mimeType = "image/png"))
        val unk = exporter.attachmentFilename(attachment(id = "c").copy(mimeType = "application/octet-stream"))
        assertTrue(jpg.endsWith(".jpg"))
        assertTrue(png.endsWith(".png"))
        assertTrue(unk.endsWith(".bin"))
    }
}
```

(`Attachment.copy(...)` requires the Kotlin stdlib `copy` on data classes — works out of the box.)

- [ ] **Step 4: Run the tests**

```
./gradlew --no-daemon :app:testDebugUnitTest --tests "com.example.aiinbox.sync.MarkdownExporterTest"
```

Expected: 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/sync/MarkdownEnvelope.kt \
        app/src/main/kotlin/com/example/aiinbox/sync/MarkdownExporter.kt \
        app/src/test/kotlin/com/example/aiinbox/sync/MarkdownExporterTest.kt
GIT_AUTHOR_NAME='nord14541' GIT_AUTHOR_EMAIL='nord14541@gmail.com' \
GIT_COMMITTER_NAME='nord14541' GIT_COMMITTER_EMAIL='nord14541@gmail.com' \
  git commit -m "feat(sync): MarkdownEnvelope + MarkdownExporter (DB row → Markdown bytes)"
```

---

## Task 4: `MarkdownImporter` + tests

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/MarkdownImporter.kt`
- Create: `app/src/test/kotlin/com/example/aiinbox/sync/MarkdownImporterTest.kt`

- [ ] **Step 1: Implement the importer**

```kotlin
package com.example.aiinbox.sync

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Markdown bytes → MarkdownEnvelope + body summary. Pure: no I/O. The
 * caller (FsSyncEngine) decides what to do with the parse result.
 */
@Singleton
class MarkdownImporter @Inject constructor() {

    private val yaml = Yaml.default

    sealed interface ParseResult {
        data class Success(val envelope: MarkdownEnvelope, val summaryBody: String) : ParseResult
        data class Failure(val reason: String) : ParseResult
    }

    fun parse(bytes: ByteArray): ParseResult {
        val text = bytes.toString(Charsets.UTF_8)
        val (front, body) = splitFrontmatter(text) ?: return ParseResult.Failure(
            "missing frontmatter delimiters"
        )
        val envelope = try {
            yaml.decodeFromString(MarkdownEnvelope.serializer(), front)
        } catch (e: YamlException) {
            return ParseResult.Failure("YAML parse error: ${e.message}")
        }
        if (envelope.id.isBlank()) return ParseResult.Failure("envelope id is blank")
        return ParseResult.Success(envelope, body.trim())
    }

    /**
     * Split a Markdown text into (frontmatter YAML, body) at the
     * `---` delimiters. Returns null if the structure is invalid.
     */
    private fun splitFrontmatter(text: String): Pair<String, String>? {
        if (!text.startsWith("---")) return null
        // Find the second `---` on its own line.
        val firstNewline = text.indexOf('\n')
        if (firstNewline < 0) return null
        val rest = text.substring(firstNewline + 1)
        val endIdx = rest.indexOf("\n---")
        if (endIdx < 0) return null
        val front = rest.substring(0, endIdx)
        val afterDelim = rest.substring(endIdx + "\n---".length)
        // Skip the rest of the second-delimiter line (could be `\n` or `---\n`).
        val body = afterDelim.removePrefix("\n").let {
            if (it.startsWith("\n")) it.substring(1) else it
        }
        return front to body
    }
}
```

- [ ] **Step 2: Write the parse tests**

`app/src/test/kotlin/com/example/aiinbox/sync/MarkdownImporterTest.kt`:

```kotlin
package com.example.aiinbox.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownImporterTest {

    private val importer = MarkdownImporter()

    @Test
    fun parse_aliveItem_returnsEnvelopeAndBody() {
        val text = """
            ---
            id: f08528b4
            received_at: "2026-05-04T12:35:34+09:00"
            updated_at: "2026-05-04T12:35:34+09:00"
            status: COMPLETED
            title: "ほっともっと"
            tags: [a, b]
            ---

            ほっともっとの注文受取案内です。
        """.trimIndent()

        val result = importer.parse(text.toByteArray(Charsets.UTF_8))
        assertTrue("expected Success, got $result", result is MarkdownImporter.ParseResult.Success)
        val ok = result as MarkdownImporter.ParseResult.Success
        assertEquals("f08528b4", ok.envelope.id)
        assertEquals("COMPLETED", ok.envelope.status)
        assertEquals(listOf("a", "b"), ok.envelope.tags)
        assertEquals("ほっともっとの注文受取案内です。", ok.summaryBody)
    }

    @Test
    fun parse_tombstone_returnsDeletedStatusAndEmptyBody() {
        val text = """
            ---
            id: del-1
            received_at: "2026-05-04T00:00:00+09:00"
            updated_at: "2026-05-04T14:00:00+09:00"
            status: DELETED
            deleted_at: "2026-05-04T14:00:00+09:00"
            ---

        """.trimIndent()

        val result = importer.parse(text.toByteArray(Charsets.UTF_8))
        assertTrue(result is MarkdownImporter.ParseResult.Success)
        val ok = result as MarkdownImporter.ParseResult.Success
        assertEquals("DELETED", ok.envelope.status)
        assertEquals("", ok.summaryBody)
    }

    @Test
    fun parse_missingFrontmatter_returnsFailure() {
        val text = "not a frontmatter file\nplain text only"
        val result = importer.parse(text.toByteArray(Charsets.UTF_8))
        assertTrue(result is MarkdownImporter.ParseResult.Failure)
    }

    @Test
    fun parse_malformedYaml_returnsFailure() {
        val text = """
            ---
            id: abc
            tags: [unterminated
            ---

            body
        """.trimIndent()
        val result = importer.parse(text.toByteArray(Charsets.UTF_8))
        assertTrue(result is MarkdownImporter.ParseResult.Failure)
    }

    @Test
    fun parse_blankId_returnsFailure() {
        val text = """
            ---
            id: ""
            received_at: "2026-05-04T00:00:00+09:00"
            updated_at: "2026-05-04T00:00:00+09:00"
            status: COMPLETED
            ---

            body
        """.trimIndent()
        val result = importer.parse(text.toByteArray(Charsets.UTF_8))
        assertTrue(result is MarkdownImporter.ParseResult.Failure)
    }

    @Test
    fun parse_unknownFields_areIgnored() {
        // kaml ignores unknown fields by default (matches kotlinx.serialization JSON).
        val text = """
            ---
            id: x
            received_at: "2026-05-04T00:00:00+09:00"
            updated_at: "2026-05-04T00:00:00+09:00"
            status: COMPLETED
            future_field: "ignore me"
            ---

            body
        """.trimIndent()
        val result = importer.parse(text.toByteArray(Charsets.UTF_8))
        assertTrue("kaml ignores unknown fields", result is MarkdownImporter.ParseResult.Success)
    }
}
```

If the unknown-fields test fails because the project's `Yaml.default` is strict, switch the importer's `Yaml` to:

```kotlin
private val yaml = Yaml(
    configuration = com.charleskorn.kaml.YamlConfiguration(strictMode = false)
)
```

- [ ] **Step 3: Run the tests**

```
./gradlew --no-daemon :app:testDebugUnitTest --tests "com.example.aiinbox.sync.MarkdownImporterTest"
```

Expected: 6 PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/sync/MarkdownImporter.kt \
        app/src/test/kotlin/com/example/aiinbox/sync/MarkdownImporterTest.kt
GIT_AUTHOR_NAME='nord14541' GIT_AUTHOR_EMAIL='nord14541@gmail.com' \
GIT_COMMITTER_NAME='nord14541' GIT_COMMITTER_EMAIL='nord14541@gmail.com' \
  git commit -m "feat(sync): MarkdownImporter (Markdown bytes → MarkdownEnvelope, with parse errors)"
```

---

## Task 5: `SafFolderAccess` + `FsSyncFolderStore`

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/SafFolderAccess.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/FsSyncFolderStore.kt`

- [ ] **Step 1: Implement `FsSyncFolderStore`**

```kotlin
package com.example.aiinbox.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the SAF tree URI the user picked. EncryptedSharedPreferences
 * here is overkill for a URI string, but it matches the pattern used by
 * HfTokenStore so the master-key plumbing is consistent across the app.
 */
@Singleton
open class FsSyncFolderStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "fs_sync_folder",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    open fun get(): String? = prefs.getString(KEY_URI, null)

    open fun set(uriString: String) {
        prefs.edit().putString(KEY_URI, uriString).apply()
    }

    open fun clear() {
        prefs.edit().remove(KEY_URI).apply()
    }

    private companion object {
        const val KEY_URI = "tree_uri"
    }
}
```

- [ ] **Step 2: Implement `SafFolderAccess`**

```kotlin
package com.example.aiinbox.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper over DocumentFile that hides the SAF noise from the
 * sync engine. All methods operate on a tree URI provided by
 * [FsSyncFolderStore]; missing-permission scenarios (user revoked the
 * grant from system settings) surface as [SafAccessException].
 */
@Singleton
class SafFolderAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    class SafAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private fun root(treeUri: String): DocumentFile {
        val uri = Uri.parse(treeUri)
        return DocumentFile.fromTreeUri(context, uri)
            ?: throw SafAccessException("could not resolve tree URI $treeUri")
    }

    /** Returns the user-friendly display name of the tree, e.g. "Inbox" or "Sync/Inbox". */
    fun displayName(treeUri: String): String? =
        runCatching { root(treeUri).name }.getOrNull()

    /** List all `.md` files at the root of the tree (does not descend). */
    fun listMarkdownFiles(treeUri: String): List<DocumentFile> {
        val r = root(treeUri)
        return r.listFiles().filter { it.isFile && (it.name?.endsWith(".md") == true) }
    }

    /** Find a child file by exact name; returns null if absent. */
    fun findChild(treeUri: String, name: String): DocumentFile? {
        return root(treeUri).listFiles().firstOrNull { it.name == name }
    }

    /** Find or create the `attachments/` subdirectory. */
    fun attachmentsDir(treeUri: String): DocumentFile {
        val r = root(treeUri)
        return r.listFiles().firstOrNull { it.isDirectory && it.name == "attachments" }
            ?: r.createDirectory("attachments")
            ?: throw SafAccessException("could not create attachments/")
    }

    /** Read the full bytes of [doc]. */
    @Throws(IOException::class)
    fun readBytes(doc: DocumentFile): ByteArray {
        val input = context.contentResolver.openInputStream(doc.uri)
            ?: throw IOException("could not open ${doc.uri}")
        return input.use { it.readBytes() }
    }

    /**
     * Atomically write [bytes] as a child of [parent] named [name]:
     * write to a `.tmp` sibling first, then rename to [name].
     * On any exception, delete the `.tmp` to avoid orphans.
     */
    @Throws(IOException::class)
    fun writeAtomically(parent: DocumentFile, name: String, mime: String, bytes: ByteArray) {
        val tmpName = "$name.tmp"
        // If a leftover .tmp from a crashed previous write exists, delete it first
        // (createFile would otherwise duplicate-name to "<tmpName> (1)").
        parent.listFiles().firstOrNull { it.name == tmpName }?.delete()
        // Same for the eventual target — the rename below assumes the slot is free.
        parent.listFiles().firstOrNull { it.name == name }?.delete()

        val tmp = parent.createFile(mime, tmpName)
            ?: throw IOException("could not createFile $tmpName")
        try {
            context.contentResolver.openOutputStream(tmp.uri, "w")?.use { it.write(bytes) }
                ?: throw IOException("could not openOutputStream for $tmpName")
            if (!tmp.renameTo(name)) {
                throw IOException("renameTo $name failed")
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
    }

    /** Delete a child by exact name. No-op if absent. */
    fun deleteByName(treeUri: String, name: String): Boolean {
        val doc = findChild(treeUri, name) ?: return false
        return doc.delete()
    }

    /** Delete a child of [parent] by exact name. */
    fun deleteByName(parent: DocumentFile, name: String): Boolean {
        val doc = parent.listFiles().firstOrNull { it.name == name } ?: return false
        return doc.delete()
    }
}
```

- [ ] **Step 3: Compile**

```
./gradlew --no-daemon :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/sync/SafFolderAccess.kt \
        app/src/main/kotlin/com/example/aiinbox/sync/FsSyncFolderStore.kt
GIT_AUTHOR_NAME='nord14541' GIT_AUTHOR_EMAIL='nord14541@gmail.com' \
GIT_COMMITTER_NAME='nord14541' GIT_COMMITTER_EMAIL='nord14541@gmail.com' \
  git commit -m "feat(sync): SafFolderAccess + FsSyncFolderStore (SAF I/O wrapper + URI persistence)"
```

---

## Task 6: `FsSyncEngine.diff` (pure function) + tests

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/FsSyncEngine.kt` (diff portion only)
- Create: `app/src/test/kotlin/com/example/aiinbox/sync/FsSyncEngineDiffTest.kt`

- [ ] **Step 1: Define the diff types**

```kotlin
package com.example.aiinbox.sync

import com.example.aiinbox.data.db.InboxRefRow

/**
 * Pure-function diff between local DB state (every id with deleted_at) and
 * the on-disk `.md` filenames keyed by id. There's no LWW here — neither
 * side propagates edits in this design — so the only outcomes are:
 * export, import, soft-delete-locally, re-export-tombstone, or skip.
 */
class FsSyncEngine {

    data class RemoteRef(val id: String, val isTombstone: Boolean)

    data class Diff(
        val export: List<String>,             // local-only OR local more recent than disk: write file
        val import: List<String>,             // remote-only and alive: insert into DB
        val softDeleteLocally: List<String>,  // local alive + disk tombstoned: tombstone the local row
        val reExportTombstone: List<String>,  // local tombstoned + disk alive: rewrite file as tombstone
        val skip: List<String>,               // both alive or both tombstoned: do nothing
    )

    companion object {
        fun diff(local: List<InboxRefRow>, remote: List<RemoteRef>): Diff {
            val export = mutableListOf<String>()
            val import = mutableListOf<String>()
            val softDelete = mutableListOf<String>()
            val reExport = mutableListOf<String>()
            val skip = mutableListOf<String>()
            val byIdLocal = local.associateBy { it.id }
            val byIdRemote = remote.associateBy { it.id }
            val ids = (byIdLocal.keys + byIdRemote.keys).sorted()
            for (id in ids) {
                val l = byIdLocal[id]
                val r = byIdRemote[id]
                when {
                    l != null && r == null -> export += id  // local-only (alive or tombstoned)
                    l == null && r != null -> {
                        // Remote-only: import only if alive. A remote tombstone
                        // for an id we never had is a no-op.
                        if (!r.isTombstone) import += id
                    }
                    l != null && r != null -> {
                        val lAlive = l.deletedAt == null
                        when {
                            lAlive && !r.isTombstone -> skip += id        // both alive
                            !lAlive && r.isTombstone -> skip += id        // both tombstoned
                            lAlive && r.isTombstone -> softDelete += id   // disk says deleted
                            !lAlive && !r.isTombstone -> reExport += id   // local says deleted, disk doesn't yet
                        }
                    }
                }
            }
            return Diff(export, import, softDelete, reExport, skip)
        }
    }
}
```

- [ ] **Step 2: Write the diff tests**

```kotlin
package com.example.aiinbox.sync

import com.example.aiinbox.data.db.InboxRefRow
import org.junit.Assert.assertEquals
import org.junit.Test

class FsSyncEngineDiffTest {

    private fun loc(id: String, deletedAt: Long? = null) = InboxRefRow(id, deletedAt, 0L)
    private fun rem(id: String, isTombstone: Boolean = false) = FsSyncEngine.RemoteRef(id, isTombstone)

    @Test
    fun localOnly_alive_isExported() {
        val d = FsSyncEngine.diff(listOf(loc("a")), emptyList())
        assertEquals(listOf("a"), d.export)
    }

    @Test
    fun localOnly_tombstoned_isExported() {
        val d = FsSyncEngine.diff(listOf(loc("a", deletedAt = 100)), emptyList())
        assertEquals(listOf("a"), d.export)  // exported as tombstone shape
    }

    @Test
    fun remoteOnly_alive_isImported() {
        val d = FsSyncEngine.diff(emptyList(), listOf(rem("a")))
        assertEquals(listOf("a"), d.import)
    }

    @Test
    fun remoteOnly_tombstoned_isNoOp() {
        val d = FsSyncEngine.diff(emptyList(), listOf(rem("a", isTombstone = true)))
        assertEquals(emptyList<String>(), d.import)
        assertEquals(emptyList<String>(), d.softDeleteLocally)
        assertEquals(emptyList<String>(), d.skip)
    }

    @Test
    fun bothAlive_isSkipped() {
        val d = FsSyncEngine.diff(listOf(loc("a")), listOf(rem("a")))
        assertEquals(listOf("a"), d.skip)
    }

    @Test
    fun bothTombstoned_isSkipped() {
        val d = FsSyncEngine.diff(listOf(loc("a", deletedAt = 100)), listOf(rem("a", isTombstone = true)))
        assertEquals(listOf("a"), d.skip)
    }

    @Test
    fun localAlive_diskTombstoned_isSoftDeletedLocally() {
        val d = FsSyncEngine.diff(listOf(loc("a")), listOf(rem("a", isTombstone = true)))
        assertEquals(listOf("a"), d.softDeleteLocally)
    }

    @Test
    fun localTombstoned_diskAlive_isReExportedAsTombstone() {
        val d = FsSyncEngine.diff(listOf(loc("a", deletedAt = 100)), listOf(rem("a")))
        assertEquals(listOf("a"), d.reExportTombstone)
    }
}
```

- [ ] **Step 3: Run the tests**

```
./gradlew --no-daemon :app:testDebugUnitTest --tests "com.example.aiinbox.sync.FsSyncEngineDiffTest"
```

Expected: 8 PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/sync/FsSyncEngine.kt \
        app/src/test/kotlin/com/example/aiinbox/sync/FsSyncEngineDiffTest.kt
GIT_AUTHOR_NAME='nord14541' GIT_AUTHOR_EMAIL='nord14541@gmail.com' \
GIT_COMMITTER_NAME='nord14541' GIT_COMMITTER_EMAIL='nord14541@gmail.com' \
  git commit -m "feat(sync): FsSyncEngine.diff (5 state-pair classification, pure)"
```

---

## Task 7: `FsSyncEngine.applyExport` / `applyImport` orchestration

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/sync/FsSyncEngine.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/repository/InboxRepository.kt` (insertFromFile already added in Task 2; this task wires it up)

- [ ] **Step 1: Replace `FsSyncEngine.kt` with the class form (orchestration + diff in companion)**

```kotlin
package com.example.aiinbox.sync

import androidx.documentfile.provider.DocumentFile
import com.example.aiinbox.data.db.Attachment
import com.example.aiinbox.data.db.AttachmentKind
import com.example.aiinbox.data.db.ExtractedEvent
import com.example.aiinbox.data.db.InboxItem
import com.example.aiinbox.data.db.InboxRefRow
import com.example.aiinbox.data.db.ItemStatus
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.data.storage.EncryptedImageStore
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FsSyncEngine @Inject constructor(
    private val repository: InboxRepository,
    private val imageStore: EncryptedImageStore,
    private val saf: SafFolderAccess,
    private val exporter: MarkdownExporter,
    private val importer: MarkdownImporter,
) {

    /** Pre-classified disk view: id → (filename → tombstone?). */
    data class RemoteRef(val id: String, val isTombstone: Boolean, val doc: DocumentFile)

    data class Diff(
        val export: List<String>,
        val import: List<String>,
        val softDeleteLocally: List<String>,
        val reExportTombstone: List<String>,
        val skip: List<String>,
    )

    /**
     * Orchestrate one sync run: list disk → diff → apply each bucket.
     */
    suspend fun runOnce(treeUri: String): SyncRunStats {
        val docs = saf.listMarkdownFiles(treeUri)
        val parsedRemote = mutableListOf<RemoteRef>()
        for (doc in docs) {
            val bytes = runCatching { saf.readBytes(doc) }.getOrNull() ?: continue
            val parsed = importer.parse(bytes)
            if (parsed is MarkdownImporter.ParseResult.Success) {
                parsedRemote += RemoteRef(parsed.envelope.id, parsed.envelope.status == "DELETED", doc)
            } else {
                android.util.Log.w(TAG, "skipping unparseable file ${doc.name}: ${(parsed as MarkdownImporter.ParseResult.Failure).reason}")
            }
        }
        val local = repository.allLocalRefs()
        val remoteRefs = parsedRemote.map { Companion.RemoteIdRef(it.id, it.isTombstone) }
        val diff = Companion.diff(local, remoteRefs)

        val byId = parsedRemote.associateBy { it.id }
        for (id in diff.export) exportOne(treeUri, id)
        for (id in diff.import) importOne(treeUri, byId.getValue(id))
        for (id in diff.softDeleteLocally) repository.softDelete(id)
        for (id in diff.reExportTombstone) exportOne(treeUri, id)

        return SyncRunStats(
            exported = diff.export.size,
            imported = diff.import.size,
            softDeletedLocally = diff.softDeleteLocally.size,
            reExportedTombstone = diff.reExportTombstone.size,
            skipped = diff.skip.size,
        )
    }

    private suspend fun exportOne(treeUri: String, id: String) {
        val full = repository.getWithAttachmentsIncludingDeleted(id) ?: return
        val item = full.item
        val bytes = exporter.encode(item, full.attachments)
        val name = exporter.filenameFor(item)

        // Use the root tree directly so existing-file replacement works.
        val root = DocumentFile.fromTreeUri(saf.androidContext, android.net.Uri.parse(treeUri))
            ?: throw SafFolderAccess.SafAccessException("could not open tree $treeUri")
        saf.writeAtomically(root, name, "text/markdown", bytes)

        // Attachment binaries.
        if (item.deletedAt == null && full.attachments.isNotEmpty()) {
            val attDir = saf.attachmentsDir(treeUri)
            for (att in full.attachments) {
                val bytesAtt = imageStore.readBytes(att.encryptedFilename) ?: continue
                saf.writeAtomically(attDir, exporter.attachmentFilename(att), att.mimeType, bytesAtt)
            }
        } else if (item.deletedAt != null) {
            // Tombstone: also remove attachment files from disk so the bytes
            // don't linger.
            runCatching {
                val attDir = saf.attachmentsDir(treeUri)
                for (att in full.attachments) {
                    saf.deleteByName(attDir, exporter.attachmentFilename(att))
                }
            }
        }
    }

    private suspend fun importOne(treeUri: String, remote: RemoteRef) {
        val parsed = importer.parse(saf.readBytes(remote.doc)) as? MarkdownImporter.ParseResult.Success ?: return
        val env = parsed.envelope
        val item = envelopeToItem(env, parsed.summaryBody)
        val attachments = mutableListOf<Attachment>()
        for (envAtt in env.attachments) {
            val attBytes = runCatching {
                val attDir = saf.attachmentsDir(treeUri)
                val ext = envAtt.file.substringAfterLast('.', "bin")
                val attDoc = attDir.listFiles().firstOrNull { it.name == "${envAtt.id}.$ext" }
                    ?: return@runCatching null
                saf.readBytes(attDoc)
            }.getOrNull() ?: continue
            // Re-encrypt locally with the receiving device's master key, under
            // the same encrypted filename the envelope refers to so the
            // attachments DB row matches.
            imageStore.writeWithName(envAtt.id + ".jpg.enc", attBytes)  // see comment below
            attachments += Attachment(
                id = envAtt.id,
                itemId = item.id,
                ordering = attachments.size,
                kind = AttachmentKind.SHARED_IMAGE,
                encryptedFilename = envAtt.id + ".jpg.enc",
                mimeType = envAtt.mime,
                widthPx = envAtt.widthPx,
                heightPx = envAtt.heightPx,
                byteSize = envAtt.byteSize,
                ocrText = envAtt.ocrText,
                ocrCompletedAt = null,
                createdAt = item.receivedAt,
            )
        }
        repository.insertFromFile(item, attachments)
    }

    private fun envelopeToItem(env: MarkdownEnvelope, body: String): InboxItem {
        return InboxItem(
            id = env.id,
            originalText = null,
            originalSubject = null,
            sourceApp = env.sourceApp,
            receivedAt = OffsetDateTime.parse(env.receivedAt).toInstant().toEpochMilli(),
            status = ItemStatus.valueOf(env.status.takeIf { it != "DELETED" } ?: "COMPLETED"),
            title = env.title,
            summary = body.takeIf { it.isNotBlank() },
            category = env.category,
            tags = env.tags,
            people = env.people,
            places = env.places,
            urls = env.urls,
            event = env.event?.let {
                ExtractedEvent(
                    title = it.title,
                    startMillis = it.start?.let(::parseInstantOrNull),
                    endMillis = it.end?.let(::parseInstantOrNull),
                    location = it.location,
                    confidence = it.confidence,
                )
            },
            userEditedFields = emptySet(),
            updatedAt = OffsetDateTime.parse(env.updatedAt).toInstant().toEpochMilli(),
            deletedAt = env.deletedAt?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() },
        )
    }

    private fun parseInstantOrNull(s: String): Long? =
        runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

    data class SyncRunStats(
        val exported: Int,
        val imported: Int,
        val softDeletedLocally: Int,
        val reExportedTombstone: Int,
        val skipped: Int,
    )

    companion object {
        private const val TAG = "FsSyncEngine"

        data class RemoteIdRef(val id: String, val isTombstone: Boolean)

        fun diff(local: List<InboxRefRow>, remote: List<RemoteIdRef>): Diff {
            val export = mutableListOf<String>()
            val import = mutableListOf<String>()
            val softDelete = mutableListOf<String>()
            val reExport = mutableListOf<String>()
            val skip = mutableListOf<String>()
            val byIdLocal = local.associateBy { it.id }
            val byIdRemote = remote.associateBy { it.id }
            val ids = (byIdLocal.keys + byIdRemote.keys).sorted()
            for (id in ids) {
                val l = byIdLocal[id]
                val r = byIdRemote[id]
                when {
                    l != null && r == null -> export += id
                    l == null && r != null -> if (!r.isTombstone) import += id
                    l != null && r != null -> {
                        val lAlive = l.deletedAt == null
                        when {
                            lAlive && !r.isTombstone -> skip += id
                            !lAlive && r.isTombstone -> skip += id
                            lAlive && r.isTombstone -> softDelete += id
                            !lAlive && !r.isTombstone -> reExport += id
                        }
                    }
                }
            }
            return Diff(export, import, softDelete, reExport, skip)
        }
    }
}
```

- [ ] **Step 2: Expose Context on `SafFolderAccess` for the Engine's tree-root resolution**

Add this to `SafFolderAccess.kt`:

```kotlin
internal val androidContext: Context get() = context
```

(The Engine needs to call `DocumentFile.fromTreeUri(context, uri)` to obtain the writable root for atomic writes. Adding the property is cleaner than passing a Context everywhere.)

- [ ] **Step 3: Update the Diff test from Task 6 to use `RemoteIdRef`**

Open `app/src/test/kotlin/com/example/aiinbox/sync/FsSyncEngineDiffTest.kt` and change the `rem(...)` helper:

```kotlin
private fun rem(id: String, isTombstone: Boolean = false) =
    FsSyncEngine.Companion.RemoteIdRef(id, isTombstone)
```

Re-run the diff tests to confirm they still pass:

```
./gradlew --no-daemon :app:testDebugUnitTest --tests "com.example.aiinbox.sync.FsSyncEngineDiffTest"
```

Expected: 8 PASS.

- [ ] **Step 4: Compile**

```
./gradlew --no-daemon :app:compileDebugKotlin
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/sync/FsSyncEngine.kt \
        app/src/main/kotlin/com/example/aiinbox/sync/SafFolderAccess.kt \
        app/src/test/kotlin/com/example/aiinbox/sync/FsSyncEngineDiffTest.kt
GIT_AUTHOR_NAME='nord14541' GIT_AUTHOR_EMAIL='nord14541@gmail.com' \
GIT_COMMITTER_NAME='nord14541' GIT_COMMITTER_EMAIL='nord14541@gmail.com' \
  git commit -m "feat(sync): FsSyncEngine.runOnce — applyExport / applyImport orchestration"
```

---

## Task 8: `FsSyncState` + `FsSyncStateRepository` + `FsSyncCoordinator` + `FsSyncWorker` + Hilt module

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/FsSyncState.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/FsSyncStateRepository.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/FsSyncCoordinator.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/work/FsSyncWorker.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/di/FsSyncModule.kt`

- [ ] **Step 1: `FsSyncState`**

```kotlin
package com.example.aiinbox.sync

sealed interface FsSyncState {
    object Idle : FsSyncState
    object Running : FsSyncState
    data class Error(val message: String?) : FsSyncState
}
```

- [ ] **Step 2: `FsSyncStateRepository`**

```kotlin
package com.example.aiinbox.sync

import com.example.aiinbox.data.db.FsSyncStateDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FsSyncStateRepository @Inject constructor(
    private val dao: FsSyncStateDao,
) {
    private val _runtime = MutableStateFlow<FsSyncState>(FsSyncState.Idle)
    val runtime: StateFlow<FsSyncState> = _runtime

    val folderUri: Flow<String?> = dao.observe().map { it?.folderUri }
    val lastFullSyncAt: Flow<Long?> = dao.observe().map { it?.lastFullSyncAt }

    fun setRunning() { _runtime.value = FsSyncState.Running }
    fun setIdle() { _runtime.value = FsSyncState.Idle }
    fun setError(message: String?) { _runtime.value = FsSyncState.Error(message) }
}
```

- [ ] **Step 3: `FsSyncCoordinator`**

```kotlin
package com.example.aiinbox.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.aiinbox.work.FsSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FsSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun requestImmediateSync() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME_ONESHOT,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FsSyncWorker>().build(),
        )
    }

    fun setPeriodicInterval(intervalMinutes: Long?) {
        val wm = WorkManager.getInstance(context)
        if (intervalMinutes == null) {
            wm.cancelUniqueWork(UNIQUE_NAME_PERIODIC)
            return
        }
        // No NetworkType constraint — file system sync is local-only; the
        // user-chosen sync tool will move files when *it* chooses.
        val req = PeriodicWorkRequestBuilder<FsSyncWorker>(intervalMinutes, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(UNIQUE_NAME_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    companion object {
        const val UNIQUE_NAME_ONESHOT = "fs_sync_oneshot"
        const val UNIQUE_NAME_PERIODIC = "fs_sync_periodic"
    }
}
```

- [ ] **Step 4: `FsSyncWorker`**

```kotlin
package com.example.aiinbox.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.aiinbox.data.db.FsSyncStateDao
import com.example.aiinbox.data.db.FsSyncStateEntity
import com.example.aiinbox.sync.FsSyncEngine
import com.example.aiinbox.sync.FsSyncFolderStore
import com.example.aiinbox.sync.FsSyncStateRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Drives one filesystem sync run.
 */
@HiltWorker
class FsSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val folderStore: FsSyncFolderStore,
    private val engine: FsSyncEngine,
    private val syncStateDao: FsSyncStateDao,
    private val syncStateRepository: FsSyncStateRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val treeUri = folderStore.get() ?: return Result.success()  // not configured: no-op
        syncStateRepository.setRunning()
        try {
            val stats = engine.runOnce(treeUri)
            android.util.Log.i(
                TAG,
                "sync done. exported=${stats.exported} imported=${stats.imported} " +
                    "softDel=${stats.softDeletedLocally} reExp=${stats.reExportedTombstone} skipped=${stats.skipped}",
            )
            val now = System.currentTimeMillis()
            syncStateDao.upsert(
                FsSyncStateEntity(id = 1, folderUri = treeUri, lastFullSyncAt = now)
            )
            syncStateRepository.setIdle()
            return Result.success()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "sync failed", t)
            syncStateRepository.setError(t.message)
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "FsSyncWorker"
    }
}
```

- [ ] **Step 5: `FsSyncModule`**

```kotlin
package com.example.aiinbox.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Empty for now — every FS sync class is constructor-injected. Exists as a
 * seam where later @Provides definitions can land if needed.
 */
@Module
@InstallIn(SingletonComponent::class)
object FsSyncModule
```

- [ ] **Step 6: Compile**

```
./gradlew --no-daemon :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/sync/FsSyncState.kt \
        app/src/main/kotlin/com/example/aiinbox/sync/FsSyncStateRepository.kt \
        app/src/main/kotlin/com/example/aiinbox/sync/FsSyncCoordinator.kt \
        app/src/main/kotlin/com/example/aiinbox/work/FsSyncWorker.kt \
        app/src/main/kotlin/com/example/aiinbox/di/FsSyncModule.kt
GIT_AUTHOR_NAME='nord14541' GIT_AUTHOR_EMAIL='nord14541@gmail.com' \
GIT_COMMITTER_NAME='nord14541' GIT_COMMITTER_EMAIL='nord14541@gmail.com' \
  git commit -m "feat(sync): FsSyncWorker, Coordinator, StateRepository"
```

---

## Task 9: Settings UI for sync folder + interval + status

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsUiState.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

- [ ] **Step 1: Extend `SettingsUiState`**

Append to the `data class SettingsUiState(...)` constructor:

```kotlin
val fsSyncFolderUri: String? = null,
val fsSyncFolderName: String? = null,
val fsSyncRuntime: com.example.aiinbox.sync.FsSyncState = com.example.aiinbox.sync.FsSyncState.Idle,
val fsSyncLastFullSyncAt: Long? = null,
val fsSyncIntervalMinutes: Long? = 30L,
```

- [ ] **Step 2: Inject deps and add actions in `SettingsViewModel`**

Add fields:

```kotlin
private val syncStateRepository: FsSyncStateRepository,
private val syncCoordinator: FsSyncCoordinator,
private val folderStore: FsSyncFolderStore,
private val syncStateDao: FsSyncStateDao,
private val safFolderAccess: SafFolderAccess,
```

Add to `init { ... }` (after the existing one, or merge):

```kotlin
val storedInterval = prefs.getLong(PREF_FS_INTERVAL, 30L)
val intervalMinutes = if (storedInterval == -1L) null else storedInterval
val storedUri = folderStore.get()
val displayName = storedUri?.let { runCatching { safFolderAccess.displayName(it) }.getOrNull() }
_state.update {
    it.copy(
        fsSyncFolderUri = storedUri,
        fsSyncFolderName = displayName,
        fsSyncIntervalMinutes = intervalMinutes,
    )
}
viewModelScope.launch {
    syncStateRepository.runtime.collect { rt -> _state.update { it.copy(fsSyncRuntime = rt) } }
}
viewModelScope.launch {
    syncStateRepository.lastFullSyncAt.collect { ts -> _state.update { it.copy(fsSyncLastFullSyncAt = ts) } }
}
```

Where `PREF_FS_INTERVAL` is a top-level const:

```kotlin
private const val PREF_FS_INTERVAL = "fs_sync_interval_minutes"
```

Add public actions:

```kotlin
fun onFsSyncFolderPicked(uri: String) {
    viewModelScope.launch {
        folderStore.set(uri)
        syncStateDao.upsert(FsSyncStateEntity(id = 1, folderUri = uri, lastFullSyncAt = null))
        val name = runCatching { safFolderAccess.displayName(uri) }.getOrNull()
        _state.update { it.copy(fsSyncFolderUri = uri, fsSyncFolderName = name) }
        // Kick off the first sync immediately.
        syncCoordinator.requestImmediateSync()
        // Re-enroll periodic at the persisted interval.
        syncCoordinator.setPeriodicInterval(_state.value.fsSyncIntervalMinutes)
    }
}

fun onFsSyncFolderCleared() {
    viewModelScope.launch {
        folderStore.clear()
        syncStateDao.upsert(FsSyncStateEntity(id = 1, folderUri = null, lastFullSyncAt = null))
        syncCoordinator.setPeriodicInterval(null)
        _state.update { it.copy(fsSyncFolderUri = null, fsSyncFolderName = null, fsSyncLastFullSyncAt = null) }
    }
}

fun onFsSyncNowClicked() = syncCoordinator.requestImmediateSync()

fun onFsSyncIntervalSelected(minutes: Long?) {
    _state.update { it.copy(fsSyncIntervalMinutes = minutes) }
    prefs.edit().putLong(PREF_FS_INTERVAL, minutes ?: -1L).apply()
    syncCoordinator.setPeriodicInterval(minutes)
}
```

(`prefs` should be the existing `SharedPreferences` field on the ViewModel; if there isn't one, add `private val prefs = application.getSharedPreferences("ai_inbox_settings", Context.MODE_PRIVATE)`.)

- [ ] **Step 3: Add the SAF picker contract + Drive-style section to `SettingsScreen`**

Inside the existing `Scaffold > Column`, add a new `Card` (after the existing model / db / version cards):

```kotlin
val safPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree(),
) { uri ->
    if (uri != null) {
        // Persist read+write permission across reboots / process death.
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        viewModel.onFsSyncFolderPicked(uri.toString())
    }
}

Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            stringResource(R.string.settings_fs_sync_section),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        if (s.fsSyncFolderUri == null) {
            Text(stringResource(R.string.settings_fs_sync_unconfigured))
            Spacer(Modifier.height(8.dp))
            Button(onClick = { safPickerLauncher.launch(null) }) {
                Text(stringResource(R.string.settings_fs_sync_pick_folder))
            }
        } else {
            Text(stringResource(R.string.settings_fs_sync_folder, s.fsSyncFolderName ?: s.fsSyncFolderUri!!))
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = { safPickerLauncher.launch(null) }) {
                    Text(stringResource(R.string.settings_fs_sync_change_folder))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = viewModel::onFsSyncFolderCleared) {
                    Text(stringResource(R.string.settings_fs_sync_clear_folder))
                }
            }
            Spacer(Modifier.height(12.dp))
            val statusText = when (val rt = s.fsSyncRuntime) {
                com.example.aiinbox.sync.FsSyncState.Idle ->
                    stringResource(R.string.settings_fs_sync_status_idle)
                com.example.aiinbox.sync.FsSyncState.Running ->
                    stringResource(R.string.settings_fs_sync_status_running)
                is com.example.aiinbox.sync.FsSyncState.Error ->
                    stringResource(R.string.settings_fs_sync_status_error, rt.message ?: "")
            }
            Text(statusText)
            s.fsSyncLastFullSyncAt?.let {
                Text(stringResource(R.string.settings_fs_sync_last_sync, formatTimestamp(it)))
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::onFsSyncNowClicked,
                enabled = s.fsSyncRuntime !is com.example.aiinbox.sync.FsSyncState.Running,
            ) { Text(stringResource(R.string.settings_fs_sync_now)) }
            Spacer(Modifier.height(8.dp))
            FsSyncIntervalDropdown(
                current = s.fsSyncIntervalMinutes,
                onSelected = viewModel::onFsSyncIntervalSelected,
            )
        }
    }
}
```

Add the dropdown helper at file scope (mirroring any existing interval dropdown):

```kotlin
@Composable
private fun FsSyncIntervalDropdown(current: Long?, onSelected: (Long?) -> Unit) {
    val options: List<Pair<Long?, String>> = listOf(
        15L to "15分", 30L to "30分", 60L to "1時間", 360L to "6時間", null to "自動のみ",
    )
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == current }?.second ?: "30分"
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(stringResource(R.string.settings_fs_sync_interval_label, label))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (m, optionLabel) ->
                DropdownMenuItem(text = { Text(optionLabel) }, onClick = { onSelected(m); expanded = false })
            }
        }
    }
}

private fun formatTimestamp(epochMs: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMs))
```

- [ ] **Step 4: Add string resources**

In `app/src/main/res/values/strings.xml`:

```xml
<string name="settings_fs_sync_section">ローカル/外部 Markdown 同期</string>
<string name="settings_fs_sync_unconfigured">同期フォルダ未設定</string>
<string name="settings_fs_sync_pick_folder">同期フォルダを選ぶ</string>
<string name="settings_fs_sync_change_folder">フォルダを変更</string>
<string name="settings_fs_sync_clear_folder">同期を解除</string>
<string name="settings_fs_sync_folder">フォルダ: %1$s</string>
<string name="settings_fs_sync_status_idle">アイドル</string>
<string name="settings_fs_sync_status_running">同期中…</string>
<string name="settings_fs_sync_status_error">エラー: %1$s</string>
<string name="settings_fs_sync_last_sync">最終同期: %1$s</string>
<string name="settings_fs_sync_now">今すぐ同期</string>
<string name="settings_fs_sync_interval_label">同期間隔: %1$s</string>
```

In `app/src/main/res/values-en/strings.xml`:

```xml
<string name="settings_fs_sync_section">Local / external Markdown sync</string>
<string name="settings_fs_sync_unconfigured">Sync folder not configured</string>
<string name="settings_fs_sync_pick_folder">Pick sync folder</string>
<string name="settings_fs_sync_change_folder">Change folder</string>
<string name="settings_fs_sync_clear_folder">Disable sync</string>
<string name="settings_fs_sync_folder">Folder: %1$s</string>
<string name="settings_fs_sync_status_idle">Idle</string>
<string name="settings_fs_sync_status_running">Syncing…</string>
<string name="settings_fs_sync_status_error">Error: %1$s</string>
<string name="settings_fs_sync_last_sync">Last sync: %1$s</string>
<string name="settings_fs_sync_now">Sync now</string>
<string name="settings_fs_sync_interval_label">Interval: %1$s</string>
```

- [ ] **Step 5: Compile**

```
./gradlew --no-daemon :app:compileDebugKotlin
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/ui/settings/ \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-en/strings.xml
GIT_AUTHOR_NAME='nord14541' GIT_AUTHOR_EMAIL='nord14541@gmail.com' \
GIT_COMMITTER_NAME='nord14541' GIT_COMMITTER_EMAIL='nord14541@gmail.com' \
  git commit -m "feat(sync): Settings UI for FS sync folder, interval, status, manual sync"
```

---

## Task 10: Trigger wiring + smoke test

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/work/SummarizeWorker.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/detail/DetailViewModel.kt`
- Create: `app/src/androidTest/kotlin/com/example/aiinbox/sync/FsSyncSafSmokeTest.kt`

- [ ] **Step 1: Trigger from `MainActivity.onCreate`**

Inject `FsSyncCoordinator` and `FsSyncFolderStore`. After `super.onCreate(...)`, before any `setContent`:

```kotlin
if (folderStore.get() != null) {
    syncCoordinator.requestImmediateSync()
    val prefs = getSharedPreferences("ai_inbox_settings", MODE_PRIVATE)
    val stored = prefs.getLong("fs_sync_interval_minutes", 30L)
    val interval: Long? = if (stored == -1L) null else stored
    syncCoordinator.setPeriodicInterval(interval)
}
```

- [ ] **Step 2: Trigger from `SummarizeWorker.success()`**

Inject `FsSyncCoordinator` and call `syncCoordinator.requestImmediateSync()` in both success paths (the placeholder return and the `Result.success()` after `applySummarizeResult`).

- [ ] **Step 3: Trigger from `DetailViewModel.finalizeDelete`**

Inject `FsSyncCoordinator` into `DetailViewModel`. After the `repository.finalizeDelete(itemId)` call inside `onDelete`'s delayed branch, add:

```kotlin
syncCoordinator.requestImmediateSync()
```

So a tombstone reaches disk within seconds rather than waiting for the next periodic.

- [ ] **Step 4: Smoke test using a temp directory as a fake SAF tree**

```kotlin
package com.example.aiinbox.sync

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aiinbox.data.db.AppDatabase
import com.example.aiinbox.data.repository.InboxRepository
import com.example.aiinbox.data.storage.EncryptedImageStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FsSyncSafSmokeTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var engine: FsSyncEngine
    @Inject lateinit var repository: InboxRepository
    @Inject lateinit var imageStore: EncryptedImageStore
    @Inject lateinit var db: AppDatabase

    @Before fun setup() = hilt.inject()

    @Test
    fun runOnce_exportsAliveLocalItemToDisk() = runBlocking {
        // Use the app's cacheDir as a fake "SAF tree" via DocumentFile.fromFile.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tmp = File(ctx.cacheDir, "fs-sync-smoke").apply {
            deleteRecursively(); mkdirs()
        }
        val tree = DocumentFile.fromFile(tmp)
        val fakeTreeUri = tree.uri.toString()

        // Seed an item via the existing test helper.
        val id = repository.createTestItemWithAttachment(
            text = "smoke",
            attachmentBytes = byteArrayOf(1, 2, 3),
        )

        engine.runOnce(fakeTreeUri)

        // Assert: a .md file matching <id> exists at the tree root.
        val files = tmp.listFiles().orEmpty()
        val md = files.firstOrNull { it.name.endsWith(".md") && it.name.contains(id) }
        assertNotNull("expected exported .md for id $id; saw ${files.joinToString { it.name }}", md)
    }
}
```

(`createTestItemWithAttachment` already exists from the closed Drive sync work; if not, add it back to `InboxRepository` as `@VisibleForTesting`.)

- [ ] **Step 5: Run smoke test**

```
./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.aiinbox.sync.FsSyncSafSmokeTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/MainActivity.kt \
        app/src/main/kotlin/com/example/aiinbox/work/SummarizeWorker.kt \
        app/src/main/kotlin/com/example/aiinbox/ui/detail/DetailViewModel.kt \
        app/src/androidTest/kotlin/com/example/aiinbox/sync/FsSyncSafSmokeTest.kt
GIT_AUTHOR_NAME='nord14541' GIT_AUTHOR_EMAIL='nord14541@gmail.com' \
GIT_COMMITTER_NAME='nord14541' GIT_COMMITTER_EMAIL='nord14541@gmail.com' \
  git commit -m "feat(sync): wire triggers (start, post-summarize, post-delete) + smoke test"
```

---

## Task 11: `FsTombstoneGcWorker`

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/work/FsTombstoneGcWorker.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/MainActivity.kt` (enroll)

- [ ] **Step 1: Implement the worker**

```kotlin
package com.example.aiinbox.work

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.aiinbox.data.db.AttachmentDao
import com.example.aiinbox.data.db.InboxDao
import com.example.aiinbox.data.storage.EncryptedImageStore
import com.example.aiinbox.sync.FsSyncFolderStore
import com.example.aiinbox.sync.MarkdownExporter
import com.example.aiinbox.sync.SafFolderAccess
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily sweep that physically removes tombstoned items older than 30 days,
 * locally and (best-effort) on the SAF tree.
 */
@HiltWorker
class FsTombstoneGcWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val inboxDao: InboxDao,
    private val attachmentDao: AttachmentDao,
    private val imageStore: EncryptedImageStore,
    private val folderStore: FsSyncFolderStore,
    private val saf: SafFolderAccess,
    private val exporter: MarkdownExporter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - GC_WINDOW_MS
        val rows = inboxDao.tombstonesOlderThan(cutoff)
        if (rows.isEmpty()) return Result.success()

        val treeUri = folderStore.get()
        for (row in rows) {
            // Best-effort SAF cleanup (only if a folder is configured).
            if (treeUri != null) {
                runCatching {
                    val name = exporter.filenameFor(row)
                    saf.deleteByName(treeUri, name)
                }.onFailure { android.util.Log.w(TAG, "remote GC failed for ${row.id}", it) }
            }

            // Local: erase any orphan encrypted bytes (defensive — finalizeDelete
            // already did this normally) then drop the rows.
            val attachments = attachmentDao.listForItemIncludingDeleted(row.id)
            attachments.forEach { runCatching { imageStore.delete(it.encryptedFilename) } }
            attachmentDao.physicalDeleteForItem(row.id)
            inboxDao.physicalDeleteById(row.id)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "FsTombstoneGcWorker"
        private const val GC_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    }
}
```

(Requires `AttachmentDao.listForItemIncludingDeleted` and `physicalDeleteForItem`. These were not added yet; add minimal versions:

```kotlin
@Query("SELECT * FROM attachments WHERE item_id = :itemId ORDER BY ordering ASC")
suspend fun listForItemIncludingDeleted(itemId: String): List<Attachment>

@Query("DELETE FROM attachments WHERE item_id = :itemId")
suspend fun physicalDeleteForItem(itemId: String)
```

…in the same DAO modify pass.)

- [ ] **Step 2: Enroll in `MainActivity.onCreate`**

```kotlin
androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
    "fs_tombstone_gc",
    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
    androidx.work.PeriodicWorkRequestBuilder<com.example.aiinbox.work.FsTombstoneGcWorker>(
        1, java.util.concurrent.TimeUnit.DAYS,
    ).build(),
)
```

- [ ] **Step 3: Compile**

```
./gradlew --no-daemon :app:compileDebugKotlin
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/work/FsTombstoneGcWorker.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/AttachmentDao.kt \
        app/src/main/kotlin/com/example/aiinbox/MainActivity.kt
GIT_AUTHOR_NAME='nord14541' GIT_AUTHOR_EMAIL='nord14541@gmail.com' \
GIT_COMMITTER_NAME='nord14541' GIT_COMMITTER_EMAIL='nord14541@gmail.com' \
  git commit -m "feat(sync): FsTombstoneGcWorker — purge >30d tombstones locally + SAF"
```

---

## Task 12: Manual test doc

**Files:**
- Create: `docs/superpowers/manual-tests/2026-05-04-fs-markdown-sync-manual.md`

- [ ] **Step 1: Write the doc**

```markdown
# Filesystem + Markdown Inbox sync — manual exercise

End-to-end checks against a real SAF folder synced by Syncthing
between two devices. Walk these scenarios before declaring the
feature shippable.

## Setup

- Two Android devices with the debug APK signed by the same
  `debug.keystore`.
- Syncthing installed and configured to share the same folder
  between both devices (e.g. `/storage/emulated/0/Documents/Inbox`).
- POST_NOTIFICATIONS granted (so the FGS / completion notifications
  are visible — denial doesn't break sync, only its visibility).
- Both devices have a model in `files/models/` (push via
  `scripts/push-model.sh` if needed).

After setup, on each device: Settings → 「同期フォルダを選ぶ」 →
pick the Syncthing-shared folder.

## Scenarios

### A — First export

On device A, open the app and Settings → 「今すぐ同期」.
Expect: in the Syncthing folder, a `2026-XX-XX-<id>.md` per existing
inbox item plus an `attachments/` directory with binaries.

### B — First import

On device B, open the app and Settings → 「今すぐ同期」 (this happens
automatically on app launch but the manual button removes timing
ambiguity).
Expect: the items A exported appear in B's inbox list within seconds
of Syncthing finishing its sync.

### C — Round-trip a new share

1. Device A: share an image to AI Inbox; wait for the completion
   notification.
2. Device A: confirm a new `.md` appeared in the synced folder.
3. Wait for Syncthing to mirror to device B.
4. Device B: open the app (which auto-syncs on launch).
5. Expect: the new item with summary, tags, and image is in B's
   inbox.

### D — Round-trip a delete

1. Device A: open the item, tap delete.
2. Device A: confirm the corresponding `.md` is rewritten with
   `status: DELETED` (open in any text editor / Obsidian).
3. Device B: sync.
4. Expect: the item disappears from B's inbox list.

### E — External edit is ignored

1. On device A, open one of the exported `.md` files in Obsidian.
2. Change the body to "EDITED EXTERNALLY" and save.
3. Device A: 「今すぐ同期」.
4. Expect: A's inbox is unchanged. The `.md` may be overwritten back
   to its previous body on the next export pass (we don't propagate
   edits) — confirm via diff if you care to see this.

### F — SAF permission revoked

1. On device A, system settings → AI Inbox → Permissions →
   「ファイルとメディア」 → revoke.
2. Open the app, Settings, 「今すぐ同期」.
3. Expect: error state appears; 「フォルダを変更」 button is
   available; re-picking restores function.
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/manual-tests/2026-05-04-fs-markdown-sync-manual.md
GIT_AUTHOR_NAME='nord14541' GIT_AUTHOR_EMAIL='nord14541@gmail.com' \
GIT_COMMITTER_NAME='nord14541' GIT_COMMITTER_EMAIL='nord14541@gmail.com' \
  git commit -m "docs(manual): two-device FS Markdown sync exercise via Syncthing"
```

---

## Task 13: Two-device end-to-end exercise (no code)

**Manual verification gate.** Walk scenarios A–F from
`docs/superpowers/manual-tests/2026-05-04-fs-markdown-sync-manual.md`.
Capture defects as new TODOs. On full pass, commit a marker:

```bash
git commit --allow-empty -m "milestone(sync): two-device FS sync manual exercise green"
```

---

## Self-review notes

**Spec coverage:**
- DB additions (`inbox_items.deleted_at`, `fs_sync_state`) → Task 1 ✓
- Tombstone behaviour (delete cycle + DAO query audit + FTS triggers) → Task 2 ✓
- MarkdownExporter pure encoder → Task 3 ✓
- MarkdownImporter pure decoder → Task 4 ✓
- SafFolderAccess (atomic write, list, read, delete) + FsSyncFolderStore → Task 5 ✓
- FsSyncEngine.diff (5 state pairs) → Task 6 ✓
- FsSyncEngine.runOnce orchestration → Task 7 ✓
- FsSyncState / Repository / Coordinator / Worker → Task 8 ✓
- Settings UI (folder picker, interval, status, sync now) → Task 9 ✓
- Triggers (start, post-summarize, post-delete, periodic) → Tasks 10 + 11 ✓
- TombstoneGcWorker → Task 11 ✓
- Manual exercise doc → Task 12 ✓
- Two-device gate → Task 13 ✓

**Known caveats for the implementer:**

- The Tasks 2 / 11 DAO additions overlap (`tombstonesOlderThan`,
  `physicalDeleteById`, `physicalDeleteForItem`,
  `listForItemIncludingDeleted`). Add them all in Task 2 if it makes
  the diff cleaner; Task 11 then only adds the worker itself. The
  plan keeps them split so each task's commit focuses on one concern.

- `kaml`'s default behaviour around unknown YAML fields and required
  vs default fields can differ from `kotlinx-serialization-json`.
  The Task 4 negative test for unknown fields includes a fallback
  configuration to switch to `strictMode = false` if needed.

- The smoke test in Task 10 uses `DocumentFile.fromFile(File)` to
  fake a SAF tree against a temp directory. This works for the Engine
  because `SafFolderAccess` only depends on `DocumentFile`'s public
  API, not on a real `content://` URI. Real SAF testing (URI
  permission, `takePersistableUriPermission`) only happens in the
  manual exercise.

- The DAO read-query audit in Task 2 Step 3 is the highest-risk
  silent-failure vector in the whole plan. If a query is missed,
  tombstoned items leak back into the UI. Use `grep -n '@Query'
  app/src/main/kotlin/com/example/aiinbox/data/db/InboxDao.kt` and
  cover every match.
