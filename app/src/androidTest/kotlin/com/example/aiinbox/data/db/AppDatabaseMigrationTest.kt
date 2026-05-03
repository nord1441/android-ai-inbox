package com.example.aiinbox.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val testDbName = "migration-test.db"
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        // SQLCipher with an empty passphrase creates an unencrypted DB but uses
        // SQLCipher's bundled SQLite (which includes FTS5 + trigram tokenizer).
        // FrameworkSQLiteOpenHelperFactory cannot be used because the system SQLite
        // on the test device does not expose FTS5.
        run {
            SqlCipherFactory.loadLibs(ApplicationProvider.getApplicationContext())
            SupportOpenHelperFactory(ByteArray(0))
        },
    )

    @Test
    fun migrate1To2_preservesExistingItems_andAddsAttachmentsTable() {
        // === v1 で 1 件挿入 ===
        var v1 = helper.createDatabase(testDbName, 1)
        v1.execSQL(
            """
            INSERT INTO inbox_items (
                id, original_text, original_subject, source_app, received_at,
                status, processing_attempts, last_error, title, summary, category,
                tags, people, places, urls,
                event_title, event_start_millis, event_end_millis, event_location, event_confidence,
                user_edited_fields, updated_at
            ) VALUES (
                'i1', 'hello', null, 'com.test', 100,
                'COMPLETED', 0, null, 'T', 'S', 'メモ',
                '[]', '[]', '[]', '[]',
                null, null, null, null, null,
                '[]', 100
            )
            """.trimIndent()
        )
        v1.close()

        // === v2 にマイグレート ===
        // validateDroppedTables=false: inbox_fts は FtsCallback で作成される virtual table で
        // Room の schema JSON には含まれないため、スキーマ検証から除外する必要がある。
        val v2 = helper.runMigrationsAndValidate(
            testDbName,
            2,
            false,
            MIGRATION_1_2,
        )

        // === 既存行が残っていることを検証 ===
        v2.query("SELECT id, original_text FROM inbox_items WHERE id = 'i1'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("i1")
            assertThat(c.getString(1)).isEqualTo("hello")
        }

        // === attachments テーブルが空で存在 ===
        v2.query("SELECT COUNT(*) FROM attachments").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }

        // === 新スキーマ：original_text に NULL が入る ===
        v2.execSQL(
            """
            INSERT INTO inbox_items (
                id, original_text, original_subject, source_app, received_at,
                status, processing_attempts, last_error, title, summary, category,
                tags, people, places, urls,
                event_title, event_start_millis, event_end_millis, event_location, event_confidence,
                user_edited_fields, updated_at
            ) VALUES (
                'i2', null, null, 'screenshot:capture', 200,
                'PENDING', 0, null, null, null, null,
                '[]', '[]', '[]', '[]',
                null, null, null, null, null,
                '[]', 200
            )
            """.trimIndent()
        )
        v2.query("SELECT original_text FROM inbox_items WHERE id = 'i2'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.isNull(0)).isTrue()
        }

        v2.close()
    }
}
