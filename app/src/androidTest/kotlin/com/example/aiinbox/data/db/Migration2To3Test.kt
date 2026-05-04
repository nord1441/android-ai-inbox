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
