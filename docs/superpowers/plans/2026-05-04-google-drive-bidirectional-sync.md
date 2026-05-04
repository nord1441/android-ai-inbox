# Google Drive bidirectional Inbox sync — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sync inbox items, summaries, attachments, and tombstones bidirectionally between multiple Android devices via the user's Google Drive `appData` scope, opt-in from Settings.

**Architecture:** Plain-text JSON payloads on Drive (server-side encryption is the confidentiality boundary). Per-item file layout with a single `manifest.json` driving diff. LWW + tombstone conflict model. Credential Manager + Authorization API for OAuth, OkHttp for REST. WorkManager-driven sync triggered on app start, after every summarize, periodically (default 30 min), and on user demand.

**Tech Stack:** Kotlin, Jetpack Compose, Room (with SQLCipher), kotlinx.serialization, OkHttp, WorkManager, Hilt, `androidx.credentials`, `play-services-auth` (Authorization API), MockWebServer (tests), Room `MigrationTestHelper`.

**Spec:** `docs/superpowers/specs/2026-05-04-google-drive-bidirectional-sync-design.md` — refer to it for the full design rationale; this plan only restates exact code/SQL where needed for execution.

---

## Pre-flight: dependencies and Hilt module

Before Task 1, add the new Gradle dependencies. Each task assumes these are present.

In `gradle/libs.versions.toml`:

```toml
[versions]
credentials = "1.3.0"
playServicesAuth = "21.2.0"
mockWebServer = "4.12.0"
roomTesting = "2.6.1"

[libraries]
androidx-credentials = { module = "androidx.credentials:credentials", version.ref = "credentials" }
androidx-credentials-play-services-auth = { module = "androidx.credentials:credentials-play-services-auth", version.ref = "credentials" }
google-id = { module = "com.google.android.libraries.identity.googleid:googleid", version = "1.1.1" }
play-services-auth = { module = "com.google.android.gms:play-services-auth", version.ref = "playServicesAuth" }
mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "mockWebServer" }
androidx-room-testing = { module = "androidx.room:room-testing", version.ref = "roomTesting" }
```

In `app/build.gradle.kts` add:

```kotlin
implementation(libs.androidx.credentials)
implementation(libs.androidx.credentials.play.services.auth)
implementation(libs.google.id)
implementation(libs.play.services.auth)

testImplementation(libs.mockwebserver)
androidTestImplementation(libs.androidx.room.testing)
```

These are added in **Task 0 (below)** with a sync + build verification.

---

## File Structure

| File | Responsibility | Created in |
|---|---|---|
| `app/src/main/kotlin/.../sync/DriveTokenStore.kt` | Token persistence (Keystore-backed prefs, mirror of HfTokenStore) | Task 3 |
| `app/src/main/kotlin/.../sync/DriveAuthRepository.kt` | OAuth: Credential Manager + Authorization API + refresh | Task 4 |
| `app/src/main/kotlin/.../sync/DriveApiClient.kt` | OkHttp wrapper for the 5 Drive REST endpoints | Task 6 |
| `app/src/main/kotlin/.../sync/SyncManifest.kt` | manifest.json wire format | Task 5 |
| `app/src/main/kotlin/.../sync/SyncEnvelope.kt` | items/{id}.json wire format | Task 5 |
| `app/src/main/kotlin/.../sync/SyncEngine.kt` | Diff + apply core | Tasks 7 + 8 |
| `app/src/main/kotlin/.../sync/SyncCoordinator.kt` | Trigger dedupe via WorkManager unique work | Task 9 |
| `app/src/main/kotlin/.../sync/SyncStateRepository.kt` | UI state SSOT | Task 9 |
| `app/src/main/kotlin/.../sync/SyncState.kt` | Sealed UI state class | Task 9 |
| `app/src/main/kotlin/.../work/SyncWorker.kt` | CoroutineWorker that drives a single sync run | Task 9 |
| `app/src/main/kotlin/.../work/TombstoneGcWorker.kt` | Daily GC of >30d tombstones | Task 12 |
| `app/src/main/kotlin/.../di/SyncModule.kt` | Hilt providers for sync subsystem | Tasks 3-9 (incremental) |
| `app/src/test/kotlin/.../sync/*.kt` | Unit tests | Tasks 5-8 |
| `app/src/androidTest/kotlin/.../data/db/Migration2To3Test.kt` | Migration smoke | Task 1 |
| `app/src/androidTest/kotlin/.../data/repository/InboxRepositoryDeleteTombstoneTest.kt` | Tombstone smoke | Task 2 |
| `app/src/androidTest/kotlin/.../sync/SyncWorkerSmokeTest.kt` | End-to-end smoke | Task 11 |
| `docs/superpowers/manual-tests/2026-05-04-drive-sync-manual.md` | Two-device exercise script | Task 13 |
| Modifications: `InboxItem`, `InboxDao`, `Migrations`, `AppDatabase`, `InboxRepository`, `SettingsScreen`, `SettingsViewModel`, `SettingsUiState`, `MainActivity`, `WorkScheduler`, `SummarizeWorker`, `Attachment`, `AttachmentDao` | (per-task) | Various |

---

## Task 0: Add Gradle dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add the version + library entries to `gradle/libs.versions.toml`**

Append the entries shown in the Pre-flight section into the existing `[versions]` and `[libraries]` blocks (alphabetical order within each block).

- [ ] **Step 2: Add `implementation(...)` and test deps to `app/build.gradle.kts`**

Add the four `implementation(libs.*)` lines into the existing `dependencies { ... }` block under the other `implementation(libs.*)` lines. Add the two `testImplementation` / `androidTestImplementation` lines into their respective groups.

- [ ] **Step 3: Sync Gradle**

In Android Studio: **File → Sync Project with Gradle Files**. From terminal: `./gradlew help`. Expected: BUILD SUCCESSFUL, no unresolved dependencies.

- [ ] **Step 4: Compile**

`./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build(sync): add credentials, identity, MockWebServer, room-testing deps"
```

---

## Task 1: DB migration `MIGRATION_2_3` and column additions

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/InboxItem.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/Attachment.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/Migrations.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/AppDatabase.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/data/db/SyncStateEntity.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/data/db/SyncStateDao.kt`
- Create: `app/src/androidTest/kotlin/com/example/aiinbox/data/db/Migration2To3Test.kt`

- [ ] **Step 1: Add `deleted_at` and `last_synced_at` to `InboxItem`**

In `InboxItem.kt`, add two columns to the `data class InboxItem(...)` constructor (after `updatedAt`):

```kotlin
@ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
@ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,
```

- [ ] **Step 2: Add `deleted_at` to `Attachment`**

In `Attachment.kt`, add to the `data class Attachment(...)` constructor:

```kotlin
@ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
```

- [ ] **Step 3: Create `SyncStateEntity` (singleton row)**

```kotlin
package com.example.aiinbox.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding cross-cutting Drive-sync state. The CHECK
 * constraint on `id = 1` is enforced in the migration SQL; Room's
 * @PrimaryKey gives us a typed accessor.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "account_email") val accountEmail: String? = null,
    @ColumnInfo(name = "last_full_sync_at") val lastFullSyncAt: Long? = null,
    @ColumnInfo(name = "last_manifest_etag") val lastManifestEtag: String? = null,
)
```

- [ ] **Step 4: Create `SyncStateDao`**

```kotlin
package com.example.aiinbox.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1 LIMIT 1")
    suspend fun get(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = 1 LIMIT 1")
    fun observe(): Flow<SyncStateEntity?>

    @Upsert
    suspend fun upsert(state: SyncStateEntity)
}
```

- [ ] **Step 5: Add `MIGRATION_2_3` to `Migrations.kt`**

Append below the existing `MIGRATION_1_2`:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE inbox_items ADD COLUMN deleted_at INTEGER")
        db.execSQL("ALTER TABLE inbox_items ADD COLUMN last_synced_at INTEGER")
        db.execSQL("ALTER TABLE attachments ADD COLUMN deleted_at INTEGER")
        db.execSQL(
            """
            CREATE TABLE sync_state (
                id INTEGER PRIMARY KEY NOT NULL CHECK(id = 1),
                account_email TEXT,
                last_full_sync_at INTEGER,
                last_manifest_etag TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO sync_state (id, account_email, last_full_sync_at, last_manifest_etag)
            VALUES (1, NULL, NULL, NULL)
            """.trimIndent()
        )
    }
}
```

- [ ] **Step 6: Wire `MIGRATION_2_3`, bump version, add `SyncStateEntity` and `SyncStateDao` in `AppDatabase`**

In `AppDatabase.kt`:

- Bump `version = 3`.
- Add `SyncStateEntity::class` to the `entities = [...]` array.
- Add `abstract fun syncStateDao(): SyncStateDao` to the abstract methods.
- Pass `MIGRATION_2_3` to `addMigrations(...)` in `SqlCipherFactory.kt` (search for existing `addMigrations` call).

- [ ] **Step 7: Export the new schema**

`./gradlew :app:kspDebugKotlin`. Expected: a new `app/schemas/com.example.aiinbox.data.db.AppDatabase/3.json` file appears. Stage it.

- [ ] **Step 8: Write the migration test**

`app/src/androidTest/kotlin/com/example/aiinbox/data/db/Migration2To3Test.kt`:

```kotlin
package com.example.aiinbox.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration2To3Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val dbName = "migration-test-2-3.db"

    @Test
    fun migrate2to3_addsColumnsAndSyncStateRow() {
        // Open at v2 and seed one row.
        helper.createDatabase(dbName, 2).use { v2 ->
            v2.execSQL(
                """
                INSERT INTO inbox_items (id, original_text, original_subject, source_app,
                  received_at, status, processing_attempts, last_error, title, summary,
                  category, tags, people, places, urls, event_title, event_start_millis,
                  event_end_millis, event_location, event_confidence, user_edited_fields,
                  updated_at)
                VALUES ('it-1', 'hello', NULL, 'test', 100, 'COMPLETED', 0, NULL,
                  't', 's', 'cat', '[]', '[]', '[]', '[]', NULL, NULL, NULL, NULL, NULL,
                  '[]', 100)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3).use { v3 ->
            v3.query("SELECT deleted_at, last_synced_at FROM inbox_items WHERE id = 'it-1'").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertNull("deleted_at must be NULL after migration", c.getString(0))
                assertNull("last_synced_at must be NULL after migration", c.getString(1))
            }
            v3.query("SELECT account_email, last_full_sync_at, last_manifest_etag FROM sync_state WHERE id = 1").use { c ->
                assertEquals("singleton row must exist", 1, c.count)
                c.moveToFirst()
                assertNull(c.getString(0))
                assertNull(c.getString(1))
                assertNull(c.getString(2))
            }
        }
    }
}
```

- [ ] **Step 9: Run the migration test**

`./gradlew :app:connectedDebugAndroidTest --tests "com.example.aiinbox.data.db.Migration2To3Test"`. Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/db/InboxItem.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/Attachment.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/Migrations.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/AppDatabase.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/SqlCipherFactory.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/SyncStateEntity.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/SyncStateDao.kt \
        app/schemas/com.example.aiinbox.data.db.AppDatabase/3.json \
        app/src/androidTest/kotlin/com/example/aiinbox/data/db/Migration2To3Test.kt
git commit -m "feat(db): add deleted_at, last_synced_at, sync_state for Drive sync"
```

---

## Task 2: Hard-delete → tombstone behaviour

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/InboxDao.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/AttachmentDao.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/repository/InboxRepository.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/db/Migrations.kt` (FTS triggers)
- Create: `app/src/androidTest/kotlin/com/example/aiinbox/data/repository/InboxRepositoryDeleteTombstoneTest.kt`

- [ ] **Step 1: Add `WHERE deleted_at IS NULL` to all read queries in `InboxDao`**

Audit every `@Query` in `InboxDao.kt`. For every SELECT that returns inbox items (FTS-based, LIKE-based, by-id, observe-all, observe-filtered, count-by-status, etc.), add `AND inbox_items.deleted_at IS NULL` (or `WHERE deleted_at IS NULL` if no WHERE existed). Tombstoned rows must not appear in any user-facing list, search, or count.

For each such query, also confirm the FTS-join SELECTs add the same predicate against `inbox_items.deleted_at`.

- [ ] **Step 2: Add tombstone setter to `InboxDao`**

Append:

```kotlin
@Query("UPDATE inbox_items SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE id = :id")
suspend fun markDeleted(id: String, deletedAt: Long)
```

(Also bumps `updated_at` so LWW sees the deletion as the latest mutation.)

- [ ] **Step 3: Add tombstone setter to `AttachmentDao`**

```kotlin
@Query("UPDATE attachments SET deleted_at = :deletedAt WHERE item_id = :itemId")
suspend fun markDeletedForItem(itemId: String, deletedAt: Long)
```

Audit existing read queries in `AttachmentDao` and add `AND deleted_at IS NULL` where attachments are returned for UI/processing.

- [ ] **Step 4: Update `Repository.delete(id)` to set tombstone**

Locate `delete(id: String)` in `InboxRepository.kt` (around line 188). Replace its body with:

```kotlin
suspend fun delete(id: String) {
    val now = System.currentTimeMillis()
    val full = dao.getWithAttachments(id)
    full?.attachments?.forEach { imageStore.delete(it.encryptedFilename) }
    attachmentDao.markDeletedForItem(id, now)
    dao.markDeleted(id, now)
}
```

The encrypted image bytes are erased immediately (no point keeping them
locally). The `deleted_at` flag is what carries the delete intent into
Drive sync.

- [ ] **Step 5: Update FTS triggers**

The existing `inbox_items_fts_*` triggers in `Migrations.kt` (search for `CREATE TRIGGER inbox_items_fts`) replicate inserts/updates/deletes into the FTS5 table. Tombstoning a row must remove it from FTS so search results are consistent with the list. Add (in `MIGRATION_2_3`, **before** `INSERT INTO sync_state`):

```kotlin
db.execSQL(
    """
    CREATE TRIGGER inbox_items_fts_tombstone AFTER UPDATE OF deleted_at ON inbox_items
    WHEN NEW.deleted_at IS NOT NULL AND OLD.deleted_at IS NULL
    BEGIN
        DELETE FROM inbox_items_fts WHERE rowid = OLD.rowid;
    END
    """.trimIndent()
)
```

Re-run `./gradlew :app:kspDebugKotlin` and update `app/schemas/.../3.json` if Room re-validates.

- [ ] **Step 6: Write the tombstone smoke test**

`app/src/androidTest/kotlin/com/example/aiinbox/data/repository/InboxRepositoryDeleteTombstoneTest.kt`:

```kotlin
package com.example.aiinbox.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiinbox.data.db.AppDatabase
import com.example.aiinbox.data.storage.EncryptedImageStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class InboxRepositoryDeleteTombstoneTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var db: AppDatabase
    @Inject lateinit var repository: InboxRepository
    @Inject lateinit var imageStore: EncryptedImageStore

    @Before fun setup() = hilt.inject()

    @Test
    fun delete_setsTombstoneAndErasesFile_butKeepsRow() = runBlocking {
        // Arrange: create an item via the repository (use the test draft helper if
        // present, otherwise use the public ingestion API).
        val id = repository.createTestItemWithAttachment(
            text = "hello",
            attachmentBytes = ByteArray(16) { 1 },
        )
        val attBefore = db.attachmentDao().listForItem(id)
        assertEquals(1, attBefore.size)
        val encName = attBefore.first().encryptedFilename
        assertNotNull(imageStore.read(encName))

        // Act
        repository.delete(id)

        // Assert: row exists with deleted_at, file is gone, observable lists are empty.
        val row = db.inboxDao().getById(id)
        assertNotNull("row must remain (tombstone, not hard delete)", row)
        assertNotNull("deleted_at must be set", row!!.deletedAt)
        assertEquals(emptyList<Any>(), repository.observeAll().first())
        assertFalse("encrypted file must be erased", imageStore.exists(encName))
    }
}
```

If `createTestItemWithAttachment` and `imageStore.exists` do not exist yet, add them as small public helpers; the test is the consumer that drives that decision.

- [ ] **Step 7: Run the test**

`./gradlew :app:connectedDebugAndroidTest --tests "com.example.aiinbox.data.repository.InboxRepositoryDeleteTombstoneTest"`. Expected: PASS.

- [ ] **Step 8: Manually verify on the device**

Run the app, share a screenshot to create an item, wait for summarize, then delete the item from the Detail screen. Reopen the app. Expected:

- The item disappears from the Inbox list.
- `adb shell 'run-as com.example.aiinbox.debug ls -la files/attachments/'` shows the encrypted bytes are gone.
- `adb logcat -d | grep -i InboxRepository` shows no errors.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/data/db/InboxDao.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/AttachmentDao.kt \
        app/src/main/kotlin/com/example/aiinbox/data/repository/InboxRepository.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/Migrations.kt \
        app/src/androidTest/kotlin/com/example/aiinbox/data/repository/InboxRepositoryDeleteTombstoneTest.kt
git commit -m "feat(repository): tombstone deletes; queries filter deleted_at IS NULL"
```

---

## Task 3: `DriveTokenStore`

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/DriveTokenStore.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/di/SyncModule.kt` (create)
- Create: `app/src/test/kotlin/com/example/aiinbox/sync/DriveTokenStoreTest.kt`

- [ ] **Step 1: Mirror `HfTokenStore` shape, but with three fields**

`DriveTokenStore.kt`:

```kotlin
package com.example.aiinbox.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists Drive OAuth tokens through Android Keystore-backed
 * EncryptedSharedPreferences. Mirrors HfTokenStore so the surrounding
 * pattern stays familiar.
 */
@Singleton
open class DriveTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtEpochMs: Long,
        val accountEmail: String,
    )

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "drive_tokens",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    open fun get(): Tokens? {
        val a = prefs.getString(KEY_ACCESS, null) ?: return null
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val expires = prefs.getLong(KEY_EXPIRES, 0L)
        if (expires == 0L) return null
        return Tokens(
            accessToken = a,
            refreshToken = prefs.getString(KEY_REFRESH, null),
            expiresAtEpochMs = expires,
            accountEmail = email,
        )
    }

    open fun put(tokens: Tokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putLong(KEY_EXPIRES, tokens.expiresAtEpochMs)
            .putString(KEY_EMAIL, tokens.accountEmail)
            .apply()
    }

    open fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_EMAIL = "account_email"
    }
}
```

- [ ] **Step 2: Create `SyncModule.kt`**

```kotlin
package com.example.aiinbox.di

import com.example.aiinbox.sync.DriveTokenStore
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    // Hilt creates DriveTokenStore via constructor injection; this module
    // exists as the seam for later providers (DriveApiClient, DriveAuthRepository,
    // etc.) that need explicit @Provides for OkHttp / Json customization.
}
```

- [ ] **Step 3: Write the round-trip test**

`app/src/test/kotlin/com/example/aiinbox/sync/DriveTokenStoreTest.kt`:

`EncryptedSharedPreferences` is hard to instantiate in Robolectric. Skip this layer in unit tests; cover it in the manual verification of Task 4. Delete this test file from the plan; instead, in this Task add only:

- [ ] **Step 3 (revised): Compile-only check**

`./gradlew :app:compileDebugKotlin`. Expected: BUILD SUCCESSFUL. (Manual verification of read/write happens in Task 4 when we link a real account.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/sync/DriveTokenStore.kt \
        app/src/main/kotlin/com/example/aiinbox/di/SyncModule.kt
git commit -m "feat(sync): add DriveTokenStore (Keystore-backed OAuth token persistence)"
```

---

## Task 4: `DriveAuthRepository` + Settings UI link/unlink (no actual sync yet)

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/DriveAuthRepository.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsUiState.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

- [ ] **Step 1: Create `DriveAuthRepository`**

```kotlin
package com.example.aiinbox.sync

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the two-step OAuth handshake:
 *   1. Credential Manager picks a Google account and returns an ID token.
 *   2. Authorization API requests the drive.appdata scope and returns
 *      access + refresh tokens.
 *
 * Tokens are persisted via DriveTokenStore. Refresh failures surface
 * via [refreshAccessToken] returning null; callers are responsible for
 * routing the user back to [link] in that case.
 */
@Singleton
open class DriveAuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenStore: DriveTokenStore,
) {

    open suspend fun link(activity: Activity): Result<DriveTokenStore.Tokens> {
        return try {
            val cm = CredentialManager.create(activity)
            val idOption = GetGoogleIdOption.Builder()
                .setServerClientId(WEB_CLIENT_ID)
                .setFilterByAuthorizedAccounts(false)
                .build()
            val cred = cm.getCredential(
                activity,
                GetCredentialRequest.Builder().addCredentialOption(idOption).build(),
            )
            val idTokenCred = GoogleIdTokenCredential.createFrom(cred.credential.data)
            val email = idTokenCred.id

            val authClient = Identity.getAuthorizationClient(activity)
            val authReq = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_APPDATA)))
                .requestOfflineAccess(WEB_CLIENT_ID, /* forceCodeForRefreshToken = */ true)
                .build()
            val authResult = authClient.authorize(authReq).await()

            // If user-consent UI is needed, the result carries a PendingIntent.
            // Caller (the screen) is responsible for launching it; here we expect
            // the consent path was already cleared. If not, surface an error.
            if (authResult.hasResolution()) {
                return Result.failure(
                    DriveAuthException("user consent required for drive.appdata", authResult.pendingIntent)
                )
            }

            val token = authResult.accessToken
                ?: return Result.failure(IllegalStateException("no access token returned"))
            val refresh = authResult.serverAuthCode  // exchanged later if needed
            val tokens = DriveTokenStore.Tokens(
                accessToken = token,
                refreshToken = refresh,
                expiresAtEpochMs = System.currentTimeMillis() + 50 * 60 * 1000L, // 50 min safety
                accountEmail = email,
            )
            tokenStore.put(tokens)
            Result.success(tokens)
        } catch (e: GetCredentialException) {
            Result.failure(e)
        }
    }

    open fun unlink() {
        tokenStore.clear()
    }

    open fun currentEmail(): String? = tokenStore.get()?.accountEmail

    /**
     * Returns a valid (non-expired) access token, refreshing if necessary.
     * Returns null if refresh is impossible — the caller must trigger reauth.
     */
    open suspend fun freshAccessToken(): String? {
        val t = tokenStore.get() ?: return null
        if (t.expiresAtEpochMs > System.currentTimeMillis() + 60_000) return t.accessToken
        // Server-side refresh via the Google OAuth token endpoint, using the
        // server auth code we requested in link(). Implement with OkHttp here;
        // the dependency is already in NetworkModule. If the refresh round trip
        // fails, return null so the caller marks the state as ReauthRequired.
        return refreshAccessTokenInternal(t)
    }

    protected open suspend fun refreshAccessTokenInternal(prev: DriveTokenStore.Tokens): String? {
        // POST https://oauth2.googleapis.com/token
        //   grant_type=refresh_token
        //   refresh_token=<prev.refreshToken>
        //   client_id=<WEB_CLIENT_ID>
        //   client_secret=<WEB_CLIENT_SECRET>   ← see implementation note below
        // Parse JSON, persist new tokens, return access_token.
        //
        // Implementation note: a public Android client cannot ship client_secret.
        // The Authorization API's serverAuthCode flow assumes a server-side
        // exchange. For a single-developer hobby project, two practical paths:
        //   (a) Embed the refresh token + client_id and use Google's OAuth
        //       endpoint with no secret (works for installed-app clients).
        //   (b) Skip refresh entirely; force re-link when the access token
        //       expires (~1h). Acceptable UX for a hobby app: user re-taps
        //       「リンクする」 every hour of active use.
        // This plan ships path (b) for v1. When silent refresh becomes a
        // priority, swap this method's body for the OAuth POST.
        return null
    }

    class DriveAuthException(
        message: String,
        val pendingIntent: android.app.PendingIntent? = null,
    ) : Exception(message)

    companion object {
        // Replace at link time with a real OAuth client ID created at
        // https://console.cloud.google.com — see manual-tests doc Task 13.
        const val WEB_CLIENT_ID = "REPLACE_ME_WEB_CLIENT_ID.apps.googleusercontent.com"
        const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    }
}
```

(For v1 we ship path (b) — re-link on expiry. The refresh implementation is a separate plan.)

- [ ] **Step 2: Add link/unlink state to `SettingsUiState`**

In `SettingsUiState.kt`, add fields:

```kotlin
val driveAccountEmail: String? = null,
val isLinkingInProgress: Boolean = false,
val driveLinkError: String? = null,
```

- [ ] **Step 3: Add link/unlink actions to `SettingsViewModel`**

In `SettingsViewModel.kt`, inject `DriveAuthRepository` and expose:

```kotlin
fun onLinkDriveClicked(activity: Activity) {
    viewModelScope.launch {
        _state.update { it.copy(isLinkingInProgress = true, driveLinkError = null) }
        val result = driveAuthRepository.link(activity)
        result.onSuccess { tokens ->
            _state.update { it.copy(
                driveAccountEmail = tokens.accountEmail,
                isLinkingInProgress = false,
            ) }
        }.onFailure { t ->
            _state.update { it.copy(
                isLinkingInProgress = false,
                driveLinkError = t.message ?: "リンクに失敗しました",
            ) }
        }
    }
}

fun onUnlinkDriveClicked() {
    driveAuthRepository.unlink()
    _state.update { it.copy(driveAccountEmail = null, driveLinkError = null) }
}

init {
    // Hydrate on startup.
    _state.update { it.copy(driveAccountEmail = driveAuthRepository.currentEmail()) }
}
```

(Adapt the `_state` accessor to whatever the existing ViewModel uses — `MutableStateFlow` or similar. If the ViewModel does not currently expose a private mutable backing flow, add one minimally.)

- [ ] **Step 4: Add the Drive section to `SettingsScreen`**

In `SettingsScreen.kt`, find the existing Scaffold/Column and add:

```kotlin
val ctx = LocalContext.current
val activity = remember(ctx) { ctx.findActivity() }

Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            stringResource(R.string.settings_drive_section),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        if (state.driveAccountEmail == null) {
            Text(stringResource(R.string.settings_drive_unlinked))
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { activity?.let(viewModel::onLinkDriveClicked) },
                enabled = !state.isLinkingInProgress && activity != null,
            ) { Text(stringResource(R.string.settings_drive_link_button)) }
        } else {
            Text(stringResource(R.string.settings_drive_linked_as, state.driveAccountEmail!!))
            Spacer(Modifier.height(8.dp))
            Button(onClick = viewModel::onUnlinkDriveClicked) {
                Text(stringResource(R.string.settings_drive_unlink_button))
            }
        }
        state.driveLinkError?.let {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_drive_error, it), color = MaterialTheme.colorScheme.error)
        }
    }
}
```

Add a top-level helper at file scope:

```kotlin
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
```

- [ ] **Step 5: Add string resources**

In `app/src/main/res/values/strings.xml`:

```xml
<string name="settings_drive_section">Google Drive 同期</string>
<string name="settings_drive_unlinked">未リンク</string>
<string name="settings_drive_link_button">Driveをリンク</string>
<string name="settings_drive_linked_as">リンク済み: %1$s</string>
<string name="settings_drive_unlink_button">リンク解除</string>
<string name="settings_drive_error">エラー: %1$s</string>
```

In `app/src/main/res/values-en/strings.xml`:

```xml
<string name="settings_drive_section">Google Drive sync</string>
<string name="settings_drive_unlinked">Not linked</string>
<string name="settings_drive_link_button">Link Drive</string>
<string name="settings_drive_linked_as">Linked as %1$s</string>
<string name="settings_drive_unlink_button">Unlink</string>
<string name="settings_drive_error">Error: %1$s</string>
```

- [ ] **Step 6: Set up the OAuth client ID**

Per the spec (Out of scope: client-secret handling): create an OAuth 2.0 client ID at https://console.cloud.google.com/apis/credentials — choose "Web application" type for use with the Authorization API. Add the SHA-1 of your debug keystore as an Android client variant. Replace `WEB_CLIENT_ID` in `DriveAuthRepository.companion` with the real value. **Do not commit the real client ID into version control if your repo is public**; for this hobby project it is acceptable inline.

- [ ] **Step 7: Compile**

`./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Manual verification — happy path**

Build & install. Open Settings → tap 「Driveをリンク」. Expected:

- Credential Manager bottom sheet shows your Google accounts; pick one.
- Drive consent screen appears mentioning "App data folder".
- After consent, Settings shows "リンク済み: <email>" and the button changes to 「リンク解除」.

`adb logcat | grep -iE 'DriveAuth|Credential|Authorization'` should show no errors.

- [ ] **Step 9: Manual verification — unlink**

Tap 「リンク解除」. Settings reverts to "未リンク". `adb shell 'run-as com.example.aiinbox.debug ls -la shared_prefs/drive_tokens.xml'` shows the file is empty (or absent).

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/sync/DriveAuthRepository.kt \
        app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsUiState.kt \
        app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsViewModel.kt \
        app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-en/strings.xml
git commit -m "feat(sync): Drive OAuth link/unlink in Settings (no sync yet)"
```

---

## Task 5: `SyncManifest` + `SyncEnvelope` wire format + serialization tests

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/SyncManifest.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/SyncEnvelope.kt`
- Create: `app/src/test/kotlin/com/example/aiinbox/sync/SyncManifestSerializationTest.kt`
- Create: `app/src/test/kotlin/com/example/aiinbox/sync/SyncEnvelopeSerializationTest.kt`

- [ ] **Step 1: Define `SyncManifest`**

```kotlin
package com.example.aiinbox.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncManifest(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("generated_at") val generatedAt: Long,
    val items: List<ManifestItem>,
) {
    @Serializable
    data class ManifestItem(
        val id: String,
        @SerialName("updated_at") val updatedAt: Long,
        @SerialName("deleted_at") val deletedAt: Long? = null,
        @SerialName("attachment_ids") val attachmentIds: List<String> = emptyList(),
        @SerialName("attachment_byte_sizes") val attachmentByteSizes: List<Long> = emptyList(),
    )
}
```

- [ ] **Step 2: Define `SyncEnvelope`**

```kotlin
package com.example.aiinbox.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for `appData/items/{id}.json`. A flat mirror of the
 * inbox_items row plus the embedded ExtractedEvent. Fields use snake_case
 * to match Drive convention and keep the JSON readable in raw form.
 */
@Serializable
data class SyncEnvelope(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val id: String,
    @SerialName("original_text") val originalText: String? = null,
    @SerialName("original_subject") val originalSubject: String? = null,
    @SerialName("source_app") val sourceApp: String? = null,
    @SerialName("received_at") val receivedAt: Long,
    val status: String,
    @SerialName("processing_attempts") val processingAttempts: Int = 0,
    @SerialName("last_error") val lastError: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val urls: List<String> = emptyList(),
    val event: EnvelopeEvent? = null,
    @SerialName("user_edited_fields") val userEditedFields: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("deleted_at") val deletedAt: Long? = null,
    val attachments: List<EnvelopeAttachment> = emptyList(),
) {
    @Serializable
    data class EnvelopeEvent(
        val title: String,
        @SerialName("start_millis") val startMillis: Long? = null,
        @SerialName("end_millis") val endMillis: Long? = null,
        val location: String? = null,
        val confidence: Float = 0.0f,
    )

    @Serializable
    data class EnvelopeAttachment(
        val id: String,
        @SerialName("item_id") val itemId: String,
        @SerialName("encrypted_filename") val encryptedFilename: String,
        @SerialName("mime_type") val mimeType: String,
        @SerialName("byte_size") val byteSize: Long,
        val kind: String,
        val ordering: Int,
        @SerialName("ocr_text") val ocrText: String? = null,
        @SerialName("ocr_completed_at") val ocrCompletedAt: Long? = null,
        @SerialName("created_at") val createdAt: Long,
        @SerialName("deleted_at") val deletedAt: Long? = null,
    )
}
```

- [ ] **Step 3: Round-trip test for manifest**

`app/src/test/kotlin/com/example/aiinbox/sync/SyncManifestSerializationTest.kt`:

```kotlin
package com.example.aiinbox.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncManifestSerializationTest {

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    @Test
    fun roundTrip_preservesSchemaVersionAndItems() {
        val original = SyncManifest(
            generatedAt = 1746345600000L,
            items = listOf(
                SyncManifest.ManifestItem(
                    id = "it-1",
                    updatedAt = 1746345500000L,
                    deletedAt = null,
                    attachmentIds = listOf("att-1"),
                    attachmentByteSizes = listOf(482311L),
                ),
                SyncManifest.ManifestItem(
                    id = "it-2",
                    updatedAt = 1746345400000L,
                    deletedAt = 1746345450000L,
                    attachmentIds = emptyList(),
                    attachmentByteSizes = emptyList(),
                ),
            ),
        )
        val text = json.encodeToString(SyncManifest.serializer(), original)
        val decoded = json.decodeFromString(SyncManifest.serializer(), text)
        assertEquals(original, decoded)
        assertEquals(1, decoded.schemaVersion)
    }

    @Test
    fun decode_acceptsUnknownFields() {
        val text = """
            {"schema_version":1,"generated_at":100,"items":[],"future_field":"ignored"}
        """.trimIndent()
        val decoded = json.decodeFromString(SyncManifest.serializer(), text)
        assertEquals(0, decoded.items.size)
    }
}
```

- [ ] **Step 4: Round-trip test for envelope**

`app/src/test/kotlin/com/example/aiinbox/sync/SyncEnvelopeSerializationTest.kt`:

```kotlin
package com.example.aiinbox.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncEnvelopeSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun roundTrip_fullPayload() {
        val original = SyncEnvelope(
            id = "it-1",
            originalText = "hello",
            sourceApp = "test",
            receivedAt = 100L,
            status = "COMPLETED",
            title = "t",
            summary = "s",
            category = "cat",
            tags = listOf("a", "b"),
            people = listOf("alice"),
            event = SyncEnvelope.EnvelopeEvent(
                title = "meet",
                startMillis = 200L,
                location = "office",
                confidence = 0.9f,
            ),
            updatedAt = 100L,
            attachments = listOf(
                SyncEnvelope.EnvelopeAttachment(
                    id = "att-1", itemId = "it-1",
                    encryptedFilename = "enc-1", mimeType = "image/png",
                    byteSize = 100L, kind = "IMAGE", ordering = 0, createdAt = 100L,
                ),
            ),
        )
        val text = json.encodeToString(SyncEnvelope.serializer(), original)
        val decoded = json.decodeFromString(SyncEnvelope.serializer(), text)
        assertEquals(original, decoded)
    }

    @Test
    fun decode_ignoresAdditionalFields() {
        val text = """
            {"id":"x","status":"PENDING","received_at":1,"updated_at":1,"new_field":42}
        """.trimIndent()
        val decoded = json.decodeFromString(SyncEnvelope.serializer(), text)
        assertEquals("x", decoded.id)
    }
}
```

- [ ] **Step 5: Run tests**

`./gradlew :app:testDebugUnitTest --tests "com.example.aiinbox.sync.Sync*SerializationTest"`. Expected: 4 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/sync/SyncManifest.kt \
        app/src/main/kotlin/com/example/aiinbox/sync/SyncEnvelope.kt \
        app/src/test/kotlin/com/example/aiinbox/sync/SyncManifestSerializationTest.kt \
        app/src/test/kotlin/com/example/aiinbox/sync/SyncEnvelopeSerializationTest.kt
git commit -m "feat(sync): SyncManifest and SyncEnvelope wire format with round-trip tests"
```

---

## Task 6: `DriveApiClient` REST wrapper + MockWebServer tests

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/DriveApiClient.kt`
- Create: `app/src/test/kotlin/com/example/aiinbox/sync/DriveApiClientTest.kt`

- [ ] **Step 1: Define the surface**

```kotlin
package com.example.aiinbox.sync

import dagger.hilt.android.scopes.SingletonComponent  // unused; for ref
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin OkHttp wrapper for the five Drive REST v3 endpoints we need against
 * the appData scope. Caller supplies the access token via [tokenProvider]
 * so token refresh / reauth policy lives outside this class.
 */
@Singleton
class DriveApiClient @Inject constructor(
    private val client: OkHttpClient,
    private val tokenProvider: suspend () -> String?,
    private val baseUrl: HttpUrl = "https://www.googleapis.com/".toHttpUrl(),
    private val uploadBaseUrl: HttpUrl = "https://www.googleapis.com/upload/".toHttpUrl(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    data class FileMetadata(val id: String, val name: String, val size: Long?, val etag: String?)

    /** GET /drive/v3/files?spaces=appDataFolder&q=name='manifest.json' */
    suspend fun findFileByName(name: String): FileMetadata? { /* see Step 2 */ TODO() }

    /** GET /drive/v3/files/{fileId}?alt=media (with optional If-None-Match) */
    suspend fun downloadBytes(fileId: String, ifNoneMatchEtag: String? = null): DownloadResult { TODO() }

    /** POST /upload/drive/v3/files?uploadType=multipart  with appDataFolder parent */
    suspend fun createFile(name: String, body: ByteArray, mimeType: String): FileMetadata { TODO() }

    /** PATCH /upload/drive/v3/files/{fileId}?uploadType=media */
    suspend fun updateFileBytes(fileId: String, body: ByteArray, mimeType: String): FileMetadata { TODO() }

    /** DELETE /drive/v3/files/{fileId} */
    suspend fun deleteFile(fileId: String) { TODO() }

    sealed interface DownloadResult {
        object NotModified : DownloadResult
        data class Body(val bytes: ByteArray, val etag: String?) : DownloadResult
    }
}
```

- [ ] **Step 2: Implement each method**

Replace each `TODO()` with an OkHttp call. Pattern for `downloadBytes`:

```kotlin
suspend fun downloadBytes(fileId: String, ifNoneMatchEtag: String? = null): DownloadResult {
    val url = baseUrl.newBuilder()
        .addPathSegments("drive/v3/files/$fileId")
        .addQueryParameter("alt", "media")
        .build()
    val req = Request.Builder()
        .url(url)
        .get()
        .header("Authorization", "Bearer ${requireToken()}")
        .apply { ifNoneMatchEtag?.let { header("If-None-Match", it) } }
        .build()
    return client.newCall(req).executeSuspending().use { resp ->
        when (resp.code) {
            304 -> DownloadResult.NotModified
            in 200..299 -> {
                val bytes = resp.body!!.bytes()
                DownloadResult.Body(bytes, resp.header("ETag"))
            }
            401 -> error("auth required")  // SyncWorker maps to ReauthRequired
            else -> error("Drive download failed: ${resp.code} ${resp.message}")
        }
    }
}

private suspend fun requireToken(): String =
    tokenProvider() ?: error("no Drive access token available")
```

(Use `okhttp3.Call.executeSuspending` from a small helper file below; or use `okhttp3.coroutines:okhttp-coroutines` if added to deps.)

Helper at the bottom of `DriveApiClient.kt`:

```kotlin
private suspend fun okhttp3.Call.executeSuspending(): okhttp3.Response =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                cont.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                cont.resumeWith(Result.success(response))
            }
        })
    }
```

`findFileByName`, `createFile`, `updateFileBytes`, `deleteFile` follow the same OkHttp pattern. `createFile` uses multipart/related with two parts: a JSON metadata part (`{"name":"...","parents":["appDataFolder"]}`) and the binary body. `updateFileBytes` uses the simple `uploadType=media` PATCH variant.

- [ ] **Step 3: MockWebServer test for the request shape**

`app/src/test/kotlin/com/example/aiinbox/sync/DriveApiClientTest.kt`:

```kotlin
package com.example.aiinbox.sync

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

class DriveApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DriveApiClient

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = DriveApiClient(
            client = OkHttpClient(),
            tokenProvider = { "test_access_token" },
            baseUrl = server.url("/"),
            uploadBaseUrl = server.url("/upload/"),
        )
    }

    @After fun tearDown() { server.shutdown() }

    @Test
    fun downloadBytes_sendsBearerAndIfNoneMatch_andHandlesNotModified() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(304))
        val result = client.downloadBytes("file-id-1", ifNoneMatchEtag = "abc")
        assertTrue(result is DriveApiClient.DownloadResult.NotModified)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/drive/v3/files/file-id-1?alt=media", recorded.path)
        assertEquals("Bearer test_access_token", recorded.getHeader("Authorization"))
        assertEquals("abc", recorded.getHeader("If-None-Match"))
    }

    @Test
    fun downloadBytes_returnsBodyAndEtagOn200() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("ETag", "etag-2")
                .setBody(Buffer().write(byteArrayOf(1, 2, 3, 4)))
        )
        val result = client.downloadBytes("file-id-2")
        assertTrue(result is DriveApiClient.DownloadResult.Body)
        val body = result as DriveApiClient.DownloadResult.Body
        assertEquals(4, body.bytes.size)
        assertEquals("etag-2", body.etag)
    }

    // Add similar tests for findFileByName, createFile (assert multipart),
    // updateFileBytes, deleteFile.
}
```

Add at minimum the `findFileByName` (with `q=` URL-encoding check), `createFile` (assert multipart `Content-Type: multipart/related; boundary=...` and that the metadata part contains `appDataFolder`), and `deleteFile` (assert path) tests in the same file before commit.

- [ ] **Step 4: Run tests**

`./gradlew :app:testDebugUnitTest --tests "com.example.aiinbox.sync.DriveApiClientTest"`. Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/sync/DriveApiClient.kt \
        app/src/test/kotlin/com/example/aiinbox/sync/DriveApiClientTest.kt
git commit -m "feat(sync): DriveApiClient REST wrapper with MockWebServer coverage"
```

---

## Task 7: `SyncEngine.diff` (pure function) + tests

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/SyncEngine.kt` (diff portion only)
- Create: `app/src/test/kotlin/com/example/aiinbox/sync/SyncEngineDiffTest.kt`

- [ ] **Step 1: Define the diff types**

In `SyncEngine.kt`:

```kotlin
package com.example.aiinbox.sync

object SyncEngine {

    /** Subset of inbox_items used by the differ. */
    data class LocalRef(
        val id: String,
        val updatedAt: Long,
        val deletedAt: Long?,
        val lastSyncedAt: Long?,
    )

    data class Diff(
        val push: List<String>,   // ids whose local state must replace Drive state
        val pull: List<String>,   // ids whose Drive state must replace local state
        val skip: List<String>,   // ids in agreement
    )

    private fun ts(updatedAt: Long, deletedAt: Long?): Long =
        maxOf(updatedAt, deletedAt ?: 0L)

    fun diff(local: List<LocalRef>, remote: List<SyncManifest.ManifestItem>): Diff {
        val push = mutableListOf<String>()
        val pull = mutableListOf<String>()
        val skip = mutableListOf<String>()
        val byIdLocal = local.associateBy { it.id }
        val byIdRemote = remote.associateBy { it.id }
        val allIds = (byIdLocal.keys + byIdRemote.keys).sorted()
        for (id in allIds) {
            val l = byIdLocal[id]
            val r = byIdRemote[id]
            when {
                l != null && r == null -> push += id
                l == null && r != null -> pull += id
                l != null && r != null -> {
                    val lts = ts(l.updatedAt, l.deletedAt)
                    val rts = ts(r.updatedAt, r.deletedAt)
                    when {
                        rts > lts -> pull += id
                        lts > rts -> push += id
                        else -> skip += id      // tie → already in sync
                    }
                }
                else -> error("unreachable")
            }
        }
        return Diff(push, pull, skip)
    }
}
```

- [ ] **Step 2: Write the diff test (six scenarios + tie)**

`app/src/test/kotlin/com/example/aiinbox/sync/SyncEngineDiffTest.kt`:

```kotlin
package com.example.aiinbox.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncEngineDiffTest {

    private fun localRef(id: String, updated: Long, deleted: Long? = null, synced: Long? = null) =
        SyncEngine.LocalRef(id, updated, deleted, synced)

    private fun remoteItem(id: String, updated: Long, deleted: Long? = null) =
        SyncManifest.ManifestItem(id, updated, deleted, emptyList(), emptyList())

    @Test
    fun localOnly_isPushed() {
        val d = SyncEngine.diff(listOf(localRef("a", 100)), emptyList())
        assertEquals(listOf("a"), d.push)
        assertEquals(emptyList<String>(), d.pull)
    }

    @Test
    fun remoteOnly_isPulled() {
        val d = SyncEngine.diff(emptyList(), listOf(remoteItem("a", 100)))
        assertEquals(listOf("a"), d.pull)
        assertEquals(emptyList<String>(), d.push)
    }

    @Test
    fun localNewer_isPushed() {
        val d = SyncEngine.diff(
            local = listOf(localRef("a", 200)),
            remote = listOf(remoteItem("a", 100)),
        )
        assertEquals(listOf("a"), d.push)
    }

    @Test
    fun remoteNewer_isPulled() {
        val d = SyncEngine.diff(
            local = listOf(localRef("a", 100)),
            remote = listOf(remoteItem("a", 200)),
        )
        assertEquals(listOf("a"), d.pull)
    }

    @Test
    fun localDeletedMoreRecentlyThanRemoteUpdated_isPushed() {
        val d = SyncEngine.diff(
            local = listOf(localRef("a", 100, deleted = 300)),
            remote = listOf(remoteItem("a", 200)),
        )
        assertEquals(listOf("a"), d.push)
    }

    @Test
    fun remoteDeletedMoreRecentlyThanLocalUpdated_isPulled() {
        val d = SyncEngine.diff(
            local = listOf(localRef("a", 200)),
            remote = listOf(remoteItem("a", 100, deleted = 300)),
        )
        assertEquals(listOf("a"), d.pull)
    }

    @Test
    fun equalTimestamps_skip() {
        val d = SyncEngine.diff(
            local = listOf(localRef("a", 100)),
            remote = listOf(remoteItem("a", 100)),
        )
        assertEquals(listOf("a"), d.skip)
    }
}
```

- [ ] **Step 3: Run tests**

`./gradlew :app:testDebugUnitTest --tests "com.example.aiinbox.sync.SyncEngineDiffTest"`. Expected: 7 PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/sync/SyncEngine.kt \
        app/src/test/kotlin/com/example/aiinbox/sync/SyncEngineDiffTest.kt
git commit -m "feat(sync): SyncEngine.diff with LWW + tombstone semantics"
```

---

## Task 8: `SyncEngine.applyPull` + `applyPush` (orchestration)

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/sync/SyncEngine.kt` (add apply methods)
- Modify: `app/src/main/kotlin/com/example/aiinbox/data/repository/InboxRepository.kt` (add upsert helpers used by sync)
- Create: `app/src/test/kotlin/com/example/aiinbox/sync/SyncEngineApplyTest.kt`

- [ ] **Step 1: Add `applyPull` and `applyPush` to `SyncEngine`**

(Convert `SyncEngine` from `object` to `class` with constructor injection of repository + image store + DriveApiClient. Tests use a fake of each.)

```kotlin
class SyncEngine @Inject constructor(
    private val api: DriveApiClient,
    private val repository: InboxRepository,
    private val imageStore: EncryptedImageStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun applyPull(idsToPull: List<String>, fileIdLookup: Map<String, String>) {
        for (id in idsToPull) {
            val itemFileId = fileIdLookup["items/$id.json"] ?: continue
            val body = api.downloadBytes(itemFileId) as? DriveApiClient.DownloadResult.Body
                ?: continue
            val env = json.decodeFromString(SyncEnvelope.serializer(), body.bytes.decodeToString())
            repository.upsertFromSync(env)  // see Step 2
            for (att in env.attachments) {
                val attFileId = fileIdLookup["attachments/${att.id}.bin"] ?: continue
                val attBody = api.downloadBytes(attFileId) as? DriveApiClient.DownloadResult.Body
                    ?: continue
                imageStore.write(att.encryptedFilename, attBody.bytes)
            }
        }
    }

    suspend fun applyPush(idsToPush: List<String>) {
        for (id in idsToPush) {
            val full = repository.getEnvelope(id) ?: continue
            val itemBytes = json.encodeToString(SyncEnvelope.serializer(), full).encodeToByteArray()
            val itemName = "items/$id.json"
            val existing = api.findFileByName(itemName)
            if (existing == null) {
                api.createFile(itemName, itemBytes, "application/json")
            } else {
                api.updateFileBytes(existing.id, itemBytes, "application/json")
            }
            for (att in full.attachments.filter { it.deletedAt == null }) {
                val attName = "attachments/${att.id}.bin"
                val attBytes = imageStore.readBytes(att.encryptedFilename) ?: continue
                val existingAtt = api.findFileByName(attName)
                if (existingAtt == null) {
                    api.createFile(attName, attBytes, att.mimeType)
                } else if (existingAtt.size != attBytes.size.toLong()) {
                    api.updateFileBytes(existingAtt.id, attBytes, att.mimeType)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Add `InboxRepository.upsertFromSync`, `getEnvelope`**

In `InboxRepository.kt`:

```kotlin
/**
 * Apply a Drive-sourced envelope to the local DB. LWW already chose this
 * envelope as the winner, so we overwrite the local row unconditionally.
 */
suspend fun upsertFromSync(env: SyncEnvelope) {
    val item = InboxItem(
        id = env.id,
        originalText = env.originalText,
        originalSubject = env.originalSubject,
        sourceApp = env.sourceApp,
        receivedAt = env.receivedAt,
        status = ItemStatus.valueOf(env.status),
        processingAttempts = env.processingAttempts,
        lastError = env.lastError,
        title = env.title,
        summary = env.summary,
        category = env.category,
        tags = env.tags,
        people = env.people,
        places = env.places,
        urls = env.urls,
        event = env.event?.let {
            ExtractedEvent(
                title = it.title,
                startMillis = it.startMillis,
                endMillis = it.endMillis,
                location = it.location,
                confidence = it.confidence,
            )
        },
        userEditedFields = env.userEditedFields.toSet(),
        updatedAt = env.updatedAt,
        deletedAt = env.deletedAt,
        lastSyncedAt = System.currentTimeMillis(),
    )
    dao.upsert(item)
    val attachments = env.attachments.map { a ->
        Attachment(
            id = a.id,
            itemId = a.itemId,
            encryptedFilename = a.encryptedFilename,
            mimeType = a.mimeType,
            byteSize = a.byteSize,
            kind = AttachmentKind.valueOf(a.kind),
            ordering = a.ordering,
            ocrText = a.ocrText,
            ocrCompletedAt = a.ocrCompletedAt,
            createdAt = a.createdAt,
            deletedAt = a.deletedAt,
        )
    }
    attachmentDao.upsertAll(attachments)
}

suspend fun getEnvelope(id: String): SyncEnvelope? {
    val full = dao.getWithAttachmentsIncludingDeleted(id) ?: return null
    return SyncEnvelope(
        id = full.item.id,
        originalText = full.item.originalText,
        originalSubject = full.item.originalSubject,
        sourceApp = full.item.sourceApp,
        receivedAt = full.item.receivedAt,
        status = full.item.status.name,
        processingAttempts = full.item.processingAttempts,
        lastError = full.item.lastError,
        title = full.item.title,
        summary = full.item.summary,
        category = full.item.category,
        tags = full.item.tags,
        people = full.item.people,
        places = full.item.places,
        urls = full.item.urls,
        event = full.item.event?.let {
            SyncEnvelope.EnvelopeEvent(
                title = it.title,
                startMillis = it.startMillis,
                endMillis = it.endMillis,
                location = it.location,
                confidence = it.confidence,
            )
        },
        userEditedFields = full.item.userEditedFields.toList(),
        updatedAt = full.item.updatedAt,
        deletedAt = full.item.deletedAt,
        attachments = full.attachments.map { a ->
            SyncEnvelope.EnvelopeAttachment(
                id = a.id, itemId = a.itemId, encryptedFilename = a.encryptedFilename,
                mimeType = a.mimeType, byteSize = a.byteSize, kind = a.kind.name,
                ordering = a.ordering, ocrText = a.ocrText, ocrCompletedAt = a.ocrCompletedAt,
                createdAt = a.createdAt, deletedAt = a.deletedAt,
            )
        },
    )
}
```

Also add `getWithAttachmentsIncludingDeleted` to `InboxDao` (a copy of `getWithAttachments` without the `deleted_at IS NULL` filter), plus `upsert(item: InboxItem)` (Room `@Upsert`) and `attachmentDao.upsertAll(...)`.

- [ ] **Step 3: Test `applyPull` and `applyPush` in isolation**

`SyncEngineApplyTest.kt` (paraphrased structure — fill in fakes):

- Fake `DriveApiClient` whose `findFileByName`/`downloadBytes` return canned responses, whose `createFile`/`updateFileBytes` record calls.
- Fake `InboxRepository.upsertFromSync` records calls; `getEnvelope` returns a canned envelope.
- Fake `EncryptedImageStore` records reads / writes.

Tests:
- `applyPull_writesEnvelopeAndAttachmentBytes` — pull one item with one attachment, assert the fake repository got `upsertFromSync(env)` and the fake store got `write(name, bytes)`.
- `applyPush_createsItemAndAttachment_whenAbsentRemotely` — push one new item, assert two `createFile` calls with the right names.
- `applyPush_updatesItemBytes_whenAlreadyPresent` — push an item whose envelope file exists; assert `updateFileBytes` not `createFile`.

(These tests are mechanical — write them following the pattern; each ~30 lines.)

- [ ] **Step 4: Run tests**

`./gradlew :app:testDebugUnitTest --tests "com.example.aiinbox.sync.SyncEngineApplyTest"`. Expected: 3 PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/sync/SyncEngine.kt \
        app/src/main/kotlin/com/example/aiinbox/data/repository/InboxRepository.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/InboxDao.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/AttachmentDao.kt \
        app/src/test/kotlin/com/example/aiinbox/sync/SyncEngineApplyTest.kt
git commit -m "feat(sync): SyncEngine.applyPull/Push + InboxRepository sync helpers"
```

---

## Task 9: `SyncStateRepository` + `SyncCoordinator` + `SyncWorker`

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/SyncState.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/SyncStateRepository.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/sync/SyncCoordinator.kt`
- Create: `app/src/main/kotlin/com/example/aiinbox/work/SyncWorker.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/work/WorkScheduler.kt`

- [ ] **Step 1: Define `SyncState`**

```kotlin
package com.example.aiinbox.sync

sealed interface SyncState {
    object Idle : SyncState
    object Running : SyncState
    data class Error(val cause: Cause, val message: String?) : SyncState
    enum class Cause { ReauthRequired, NetworkUnavailable, QuotaExceeded, Other }
}
```

- [ ] **Step 2: `SyncStateRepository`**

```kotlin
@Singleton
class SyncStateRepository @Inject constructor(
    private val syncStateDao: SyncStateDao,
    private val authRepository: DriveAuthRepository,
) {
    private val _runtime = MutableStateFlow<SyncState>(SyncState.Idle)
    val runtime: StateFlow<SyncState> = _runtime

    val accountEmail: Flow<String?> = syncStateDao.observe().map { it?.accountEmail }
    val lastFullSyncAt: Flow<Long?> = syncStateDao.observe().map { it?.lastFullSyncAt }

    fun setRunning() { _runtime.value = SyncState.Running }
    fun setIdle() { _runtime.value = SyncState.Idle }
    fun setError(cause: SyncState.Cause, message: String?) {
        _runtime.value = SyncState.Error(cause, message)
    }
}
```

- [ ] **Step 3: `SyncCoordinator`**

```kotlin
@Singleton
class SyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun requestImmediateSync() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME_ONESHOT,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().build(),
        )
    }

    fun setPeriodicInterval(intervalMinutes: Long?) {
        val wm = WorkManager.getInstance(context)
        if (intervalMinutes == null) {
            wm.cancelUniqueWork(UNIQUE_NAME_PERIODIC)
            return
        }
        val req = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_NAME_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    companion object {
        const val UNIQUE_NAME_ONESHOT = "drive_sync_oneshot"
        const val UNIQUE_NAME_PERIODIC = "drive_sync_periodic"
    }
}
```

- [ ] **Step 4: `SyncWorker`**

```kotlin
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val authRepository: DriveAuthRepository,
    private val api: DriveApiClient,
    private val engine: SyncEngine,
    private val syncStateDao: SyncStateDao,
    private val syncStateRepository: SyncStateRepository,
    private val inboxDao: InboxDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val token = authRepository.freshAccessToken()
            ?: run {
                syncStateRepository.setError(SyncState.Cause.ReauthRequired, "再リンクしてください")
                return Result.failure()
            }
        syncStateRepository.setRunning()
        try {
            val state = syncStateDao.get()
            val manifestFile = api.findFileByName("manifest.json")
            val manifest: SyncManifest = if (manifestFile == null) {
                SyncManifest(generatedAt = System.currentTimeMillis(), items = emptyList())
            } else {
                when (val r = api.downloadBytes(manifestFile.id, ifNoneMatchEtag = state?.lastManifestEtag)) {
                    DriveApiClient.DownloadResult.NotModified -> SyncManifest(
                        generatedAt = System.currentTimeMillis(),
                        items = emptyList(),  // empty triggers push-only path below
                    )
                    is DriveApiClient.DownloadResult.Body -> Json {ignoreUnknownKeys = true}.decodeFromString(SyncManifest.serializer(), r.bytes.decodeToString())
                }
            }
            val localRefs = inboxDao.allRefsIncludingDeleted().map {
                SyncEngine.LocalRef(it.id, it.updatedAt, it.deletedAt, it.lastSyncedAt)
            }
            val diff = SyncEngine.diff(localRefs, manifest.items)

            // Build fileIdLookup for pulls (one Drive list query, cached).
            val fileIdLookup: Map<String, String> = api.listAllFileNamesAndIds()
                // returns Map<name, fileId> for everything in appData
            engine.applyPull(diff.pull, fileIdLookup)
            engine.applyPush(diff.push)

            // Re-publish manifest with post-sync state.
            val newManifest = buildManifest(System.currentTimeMillis(), inboxDao, repository = null /* see note */)
            val manifestBytes = Json {encodeDefaults = true}.encodeToString(SyncManifest.serializer(), newManifest).encodeToByteArray()
            val newMeta = if (manifestFile == null) {
                api.createFile("manifest.json", manifestBytes, "application/json")
            } else {
                api.updateFileBytes(manifestFile.id, manifestBytes, "application/json")
            }
            syncStateDao.upsert(
                SyncStateEntity(
                    id = 1,
                    accountEmail = state?.accountEmail,
                    lastFullSyncAt = System.currentTimeMillis(),
                    lastManifestEtag = newMeta.etag,
                )
            )
            syncStateRepository.setIdle()
            return Result.success()
        } catch (t: Throwable) {
            android.util.Log.e("SyncWorker", "sync failed", t)
            syncStateRepository.setError(SyncState.Cause.Other, t.message)
            return Result.retry()
        }
    }
}
```

`InboxDao.allRefsIncludingDeleted()` is a new query that returns `(id, updated_at, deleted_at, last_synced_at)` rows including tombstoned ones. `api.listAllFileNamesAndIds()` is a small Drive list helper iterating with `pageToken`. Both are added in this task as small helpers.

`buildManifest` is a top-level helper in `SyncEngine.kt`:

```kotlin
suspend fun buildManifest(now: Long, inboxDao: InboxDao, attachmentDao: AttachmentDao): SyncManifest {
    val refs = inboxDao.allRefsIncludingDeleted()
    val items = refs.map {
        val atts = attachmentDao.listForItemIncludingDeleted(it.id)
        SyncManifest.ManifestItem(
            id = it.id,
            updatedAt = it.updatedAt,
            deletedAt = it.deletedAt,
            attachmentIds = atts.map { a -> a.id },
            attachmentByteSizes = atts.map { a -> a.byteSize },
        )
    }
    return SyncManifest(generatedAt = now, items = items)
}
```

- [ ] **Step 5: Compile**

`./gradlew :app:assembleDebug`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/sync/SyncState.kt \
        app/src/main/kotlin/com/example/aiinbox/sync/SyncStateRepository.kt \
        app/src/main/kotlin/com/example/aiinbox/sync/SyncCoordinator.kt \
        app/src/main/kotlin/com/example/aiinbox/sync/SyncEngine.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/InboxDao.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/AttachmentDao.kt \
        app/src/main/kotlin/com/example/aiinbox/work/SyncWorker.kt
git commit -m "feat(sync): SyncWorker, SyncCoordinator, SyncStateRepository"
```

---

## Task 10: Settings UI for sync interval + "今すぐ同期" + status

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsUiState.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-en/strings.xml`

- [ ] **Step 1: Extend `SettingsUiState`**

```kotlin
val syncRuntime: SyncState = SyncState.Idle,
val lastFullSyncAt: Long? = null,
val syncIntervalMinutes: Long? = 30L,  // null = manual-only
```

- [ ] **Step 2: Extend `SettingsViewModel`**

Inject `SyncStateRepository` and `SyncCoordinator`. In `init`, observe `syncStateRepository.runtime` and `lastFullSyncAt` and merge into `_state`. Add:

```kotlin
fun onSyncNowClicked() = syncCoordinator.requestImmediateSync()

fun onSyncIntervalSelected(minutes: Long?) {
    _state.update { it.copy(syncIntervalMinutes = minutes) }
    syncCoordinator.setPeriodicInterval(minutes)
}
```

Persist `syncIntervalMinutes` in `SharedPreferences` (existing pattern in the app, or add a tiny one).

- [ ] **Step 3: Extend the Drive section in `SettingsScreen`**

Inside the existing Drive `Card`, after the link state, render:

```kotlin
if (state.driveAccountEmail != null) {
    Spacer(Modifier.height(12.dp))
    val statusText = when (val s = state.syncRuntime) {
        SyncState.Idle -> stringResource(R.string.settings_drive_status_idle)
        SyncState.Running -> stringResource(R.string.settings_drive_status_running)
        is SyncState.Error -> stringResource(R.string.settings_drive_status_error, s.message ?: "")
    }
    Text(statusText)
    state.lastFullSyncAt?.let {
        Text(stringResource(R.string.settings_drive_last_sync, formatTimestamp(it)))
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = viewModel::onSyncNowClicked,
        enabled = state.syncRuntime !is SyncState.Running,
    ) { Text(stringResource(R.string.settings_drive_sync_now)) }

    Spacer(Modifier.height(8.dp))
    SyncIntervalDropdown(
        current = state.syncIntervalMinutes,
        onSelected = viewModel::onSyncIntervalSelected,
    )
}
```

`SyncIntervalDropdown` is a `private @Composable` in the same file:

```kotlin
@Composable
private fun SyncIntervalDropdown(current: Long?, onSelected: (Long?) -> Unit) {
    val options = listOf(15L to "15分", 30L to "30分", 60L to "1時間", 360L to "6時間", null to "自動のみ")
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == current }?.second ?: "30分"
    Box {
        TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.settings_drive_interval_label, label)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (m, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelected(m); expanded = false })
            }
        }
    }
}

private fun formatTimestamp(epochMs: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMs))
```

- [ ] **Step 4: Add string resources**

```xml
<!-- values/strings.xml -->
<string name="settings_drive_status_idle">アイドル</string>
<string name="settings_drive_status_running">同期中…</string>
<string name="settings_drive_status_error">エラー: %1$s</string>
<string name="settings_drive_last_sync">最終同期: %1$s</string>
<string name="settings_drive_sync_now">今すぐ同期</string>
<string name="settings_drive_interval_label">同期間隔: %1$s</string>
```

(Mirror in `values-en/strings.xml`.)

- [ ] **Step 5: Compile**

`./gradlew :app:assembleDebug`.

- [ ] **Step 6: Manual verification**

Build, install, link Drive, hit 「今すぐ同期」. Expected:

- Status flips to "同期中…" → "アイドル"
- "最終同期: <now>" appears
- `adb shell` and inspect Drive via the web at https://drive.google.com/drive/u/0/settings → "Manage apps" → AI Inbox shows storage used

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/ui/settings/ \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-en/strings.xml
git commit -m "feat(sync): Settings UI for sync interval, manual sync, and status"
```

---

## Task 11: Trigger wiring (start, post-summarize, periodic) + `SyncWorkerSmokeTest`

**Files:**
- Modify: `app/src/main/kotlin/com/example/aiinbox/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/work/WorkScheduler.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/work/SummarizeWorker.kt` (chain on success)
- Create: `app/src/androidTest/kotlin/com/example/aiinbox/sync/SyncWorkerSmokeTest.kt`

- [ ] **Step 1: Trigger sync from `MainActivity.onCreate`**

Inject `SyncCoordinator` and call `syncCoordinator.requestImmediateSync()` after `super.onCreate(...)`. Guard with `if (driveAuthRepository.currentEmail() != null)` to avoid pointless WorkManager noise when not linked.

- [ ] **Step 2: Chain SyncWorker after SummarizeWorker success**

In `WorkScheduler.kt`'s function that enqueues `SummarizeWorker`, change from `enqueue(...)` to `beginUniqueWork(itemUniqueName, ...).then(syncRequest).enqueue()`. The `syncRequest` is a `OneTimeWorkRequestBuilder<SyncWorker>().build()`. Adjust as needed to your existing chain pattern.

- [ ] **Step 3: Re-enroll periodic on app start**

In `MainActivity.onCreate`, also call `syncCoordinator.setPeriodicInterval(prefs.getSyncIntervalMinutes())`. (`prefs.getSyncIntervalMinutes()` is a tiny SharedPreferences helper added in Task 10 alongside the Settings update.)

- [ ] **Step 4: Smoke-test `SyncWorker` end-to-end**

`SyncWorkerSmokeTest.kt` with Hilt + a stubbed `DriveApiClient`:

- Stubs `findFileByName`/`downloadBytes` to return a known manifest with one remote-only item and one envelope.
- Runs `SyncWorker.doWork()` directly (use `WorkManagerTestInitHelper` or invoke the worker via Hilt's `WorkerFactory`).
- Asserts the local DB now has the item.
- Asserts the test DB has `sync_state.last_full_sync_at` populated.

(Stub the `DriveApiClient` via a Hilt test module that overrides `SyncModule`.)

- [ ] **Step 5: Run smoke**

`./gradlew :app:connectedDebugAndroidTest --tests "com.example.aiinbox.sync.SyncWorkerSmokeTest"`. Expected: PASS.

- [ ] **Step 6: Manual verification**

Build, install, link Drive, share an image. Expected logcat sequence:

```
I/SummarizeWorker: Summarize success...
I/SyncWorker: sync run start
I/SyncWorker: pushed 1 / pulled 0 / skipped N
I/SyncWorker: sync run end
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/MainActivity.kt \
        app/src/main/kotlin/com/example/aiinbox/work/WorkScheduler.kt \
        app/src/main/kotlin/com/example/aiinbox/work/SummarizeWorker.kt \
        app/src/androidTest/kotlin/com/example/aiinbox/sync/SyncWorkerSmokeTest.kt
git commit -m "feat(sync): wire triggers (start, post-summarize, periodic) + smoke test"
```

---

## Task 12: `TombstoneGcWorker`

**Files:**
- Create: `app/src/main/kotlin/com/example/aiinbox/work/TombstoneGcWorker.kt`
- Modify: `app/src/main/kotlin/com/example/aiinbox/MainActivity.kt` (enroll on start)

- [ ] **Step 1: Implement `TombstoneGcWorker`**

```kotlin
@HiltWorker
class TombstoneGcWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val inboxDao: InboxDao,
    private val attachmentDao: AttachmentDao,
    private val api: DriveApiClient,
    private val authRepository: DriveAuthRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val rows = inboxDao.tombstonesOlderThan(cutoff)
        for (row in rows) {
            // Drive deletes (best-effort; if not linked, skip remote portion).
            if (authRepository.currentEmail() != null) {
                api.findFileByName("items/${row.id}.json")?.let { api.deleteFile(it.id) }
                attachmentDao.listForItemIncludingDeleted(row.id).forEach { a ->
                    api.findFileByName("attachments/${a.id}.bin")?.let { api.deleteFile(it.id) }
                }
            }
            attachmentDao.physicalDeleteForItem(row.id)
            inboxDao.physicalDeleteById(row.id)
        }
        return Result.success()
    }
}
```

Add to DAOs:

```kotlin
// InboxDao
@Query("SELECT * FROM inbox_items WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
suspend fun tombstonesOlderThan(cutoff: Long): List<InboxItem>

@Query("DELETE FROM inbox_items WHERE id = :id")
suspend fun physicalDeleteById(id: String)
```

Mirror on `AttachmentDao`.

- [ ] **Step 2: Enroll daily Periodic in `MainActivity.onCreate`**

```kotlin
WorkManager.getInstance(this).enqueueUniquePeriodicWork(
    "tombstone_gc",
    ExistingPeriodicWorkPolicy.KEEP,
    PeriodicWorkRequestBuilder<TombstoneGcWorker>(1, TimeUnit.DAYS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
        .build(),
)
```

- [ ] **Step 3: Compile**

`./gradlew :app:assembleDebug`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/example/aiinbox/work/TombstoneGcWorker.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/InboxDao.kt \
        app/src/main/kotlin/com/example/aiinbox/data/db/AttachmentDao.kt \
        app/src/main/kotlin/com/example/aiinbox/MainActivity.kt
git commit -m "feat(sync): TombstoneGcWorker — physically purge tombstones >30d"
```

---

## Task 13: Manual test doc

**Files:**
- Create: `docs/superpowers/manual-tests/2026-05-04-drive-sync-manual.md`

- [ ] **Step 1: Write the doc**

Cover the five scenarios from the spec's Manual section:

```markdown
# Drive sync — manual two-device exercise

## Setup
- Two Android devices (or one device + one emulator), both with the
  debug APK installed and the same Google account available.
- A `WEB_CLIENT_ID` configured in `DriveAuthRepository.companion`.

## A. Initial link, push side
1. On device A, Settings → Driveをリンク.
2. Pick the Google account.
3. Approve the "App data folder" consent.
4. Expect "リンク済み: <email>" + a 「リンク解除」button.

## B. Initial link, pull side
1. On device B (no local items), Settings → Driveをリンク. Same account.
2. Tap 「今すぐ同期」.
3. Expect the items shared on A in step C of the previous exercise to
   appear in the Inbox list within ~30 s.

## C. Round-trip a new share
1. Device A: share an image to AI Inbox. Wait for the "完了" notification.
2. Device B: open the app (or wait up to 30 min for the periodic).
3. Expect the new item with its summary and image to appear.

## D. Round-trip a delete
1. Device A: long-press an existing item → 削除.
2. Device B: open the app.
3. Expect the item to disappear within ~30 s.

## E. Wi-Fi off / on
1. Device A: turn off Wi-Fi and mobile data, share an image.
2. Settings shows "エラー: Networkが利用できません".
3. Re-enable Wi-Fi.
4. Expect Settings to flip back to "アイドル" and the item to appear on
   B within ~30 s.

## F. Re-auth
1. Visit https://myaccount.google.com/permissions on the same Google
   account, revoke "AI Inbox" access.
2. Tap 「今すぐ同期」 in the app.
3. Settings shows "エラー: 再リンクしてください".
4. Tap 「リンクする」 and re-grant.
5. Expect "アイドル" and a successful manual sync next.
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/manual-tests/2026-05-04-drive-sync-manual.md
git commit -m "docs(manual): two-device Drive sync exercise"
```

---

## Task 14: Two-device end-to-end exercise (no code)

This task is a **manual verification gate** before marking the feature done. There is no code in this task; it ensures the feature actually works on a real second device, which the unit/instrumented tests cannot prove.

- [ ] **Step 1: Run scenarios A through F from `2026-05-04-drive-sync-manual.md`**.
- [ ] **Step 2: Capture any defects as new TODO items / spec amendments.**
- [ ] **Step 3: If all pass, commit a marker.**

```bash
git commit --allow-empty -m "milestone(sync): two-device manual exercise green"
```

---

## Self-review notes

**Spec coverage**:
- DB migration (Task 1) ✓
- Tombstone behaviour (Task 2) ✓
- DriveTokenStore (Task 3) ✓
- DriveAuthRepository + Settings link/unlink (Task 4) ✓
- Wire format (Task 5) ✓
- DriveApiClient (Task 6) ✓
- SyncEngine.diff (Task 7) ✓
- SyncEngine.applyPull/Push + repository helpers (Task 8) ✓
- SyncStateRepository / SyncCoordinator / SyncWorker (Task 9) ✓
- Settings UI for interval / sync now / status (Task 10) ✓
- Triggers (Task 11) ✓
- TombstoneGcWorker (Task 12) ✓
- Manual test doc (Task 13) ✓
- Two-device exercise (Task 14) ✓

**Known caveats** for the implementer:

- `WEB_CLIENT_ID` is a deployment-time constant. Manual setup at Google Cloud Console is required and is documented in Task 4 Step 6.
- Token refresh (silent) is **out of scope for v1**. Task 4 ships path (b): re-link on expiry. When the user starts feeling friction, plan a follow-up task that implements `refreshAccessTokenInternal` against `https://oauth2.googleapis.com/token`.
- The DAO query audit in Task 2 Step 1 is essential — if any read query is missed, tombstoned items will leak back into the UI.
- The 412 ETag-conflict retry path mentioned in the spec's error matrix is not separately tested in unit tests; it is exercised opportunistically when two devices both try to publish a manifest in the same window. If it bites in practice, add a unit test then.
