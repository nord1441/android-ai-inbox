package com.example.aiinbox.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

object FtsCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        createFtsTable(db)
        createTriggers(db)
    }

    fun createFtsTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS inbox_fts USING fts5(
                id UNINDEXED,
                title,
                summary,
                original_text,
                tags,
                people,
                places,
                ocr_text,
                tokenize='trigram'
            )
            """.trimIndent()
        )
    }

    fun createTriggers(db: SupportSQLiteDatabase) {
        // === inbox_items 側トリガ：ocr_text 列を attachments から集計 ===
        // Only index non-tombstoned rows; tombstoned rows must not enter FTS.
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS inbox_items_ai AFTER INSERT ON inbox_items BEGIN
                INSERT INTO inbox_fts(id, title, summary, original_text, tags, people, places, ocr_text)
                SELECT new.id,
                       coalesce(new.title, ''),
                       coalesce(new.summary, ''),
                       coalesce(new.original_text, ''),
                       coalesce(new.tags, ''),
                       coalesce(new.people, ''),
                       coalesce(new.places, ''),
                       coalesce(
                           (SELECT GROUP_CONCAT(ocr_text, ' ')
                              FROM attachments WHERE item_id = new.id),
                           ''
                       )
                WHERE new.deleted_at IS NULL;
            END;
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS inbox_items_ad AFTER DELETE ON inbox_items BEGIN
                DELETE FROM inbox_fts WHERE id = old.id;
            END;
            """.trimIndent()
        )
        // On update: always remove the old FTS row, then re-insert only if not tombstoned.
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS inbox_items_au AFTER UPDATE ON inbox_items BEGIN
                DELETE FROM inbox_fts WHERE id = old.id;
                INSERT INTO inbox_fts(id, title, summary, original_text, tags, people, places, ocr_text)
                SELECT new.id,
                       coalesce(new.title, ''),
                       coalesce(new.summary, ''),
                       coalesce(new.original_text, ''),
                       coalesce(new.tags, ''),
                       coalesce(new.people, ''),
                       coalesce(new.places, ''),
                       coalesce(
                           (SELECT GROUP_CONCAT(ocr_text, ' ')
                              FROM attachments WHERE item_id = new.id),
                           ''
                       )
                WHERE new.deleted_at IS NULL;
            END;
            """.trimIndent()
        )

        // === attachments 側トリガ：item_id の FTS 行を再構築 ===
        // Guard against resurrecting a tombstoned parent's FTS row.
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS attachments_ai AFTER INSERT ON attachments BEGIN
                DELETE FROM inbox_fts WHERE id = new.item_id;
                INSERT INTO inbox_fts(id, title, summary, original_text, tags, people, places, ocr_text)
                SELECT i.id,
                       coalesce(i.title, ''),
                       coalesce(i.summary, ''),
                       coalesce(i.original_text, ''),
                       coalesce(i.tags, ''),
                       coalesce(i.people, ''),
                       coalesce(i.places, ''),
                       coalesce(
                           (SELECT GROUP_CONCAT(ocr_text, ' ')
                              FROM attachments WHERE item_id = i.id),
                           ''
                       )
                  FROM inbox_items i WHERE i.id = new.item_id AND i.deleted_at IS NULL;
            END;
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS attachments_au AFTER UPDATE ON attachments BEGIN
                DELETE FROM inbox_fts WHERE id = new.item_id;
                INSERT INTO inbox_fts(id, title, summary, original_text, tags, people, places, ocr_text)
                SELECT i.id,
                       coalesce(i.title, ''),
                       coalesce(i.summary, ''),
                       coalesce(i.original_text, ''),
                       coalesce(i.tags, ''),
                       coalesce(i.people, ''),
                       coalesce(i.places, ''),
                       coalesce(
                           (SELECT GROUP_CONCAT(ocr_text, ' ')
                              FROM attachments WHERE item_id = i.id),
                           ''
                       )
                  FROM inbox_items i WHERE i.id = new.item_id AND i.deleted_at IS NULL;
            END;
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS attachments_ad AFTER DELETE ON attachments BEGIN
                DELETE FROM inbox_fts WHERE id = old.item_id;
                INSERT INTO inbox_fts(id, title, summary, original_text, tags, people, places, ocr_text)
                SELECT i.id,
                       coalesce(i.title, ''),
                       coalesce(i.summary, ''),
                       coalesce(i.original_text, ''),
                       coalesce(i.tags, ''),
                       coalesce(i.people, ''),
                       coalesce(i.places, ''),
                       coalesce(
                           (SELECT GROUP_CONCAT(ocr_text, ' ')
                              FROM attachments WHERE item_id = i.id),
                           ''
                       )
                  FROM inbox_items i WHERE i.id = old.item_id AND i.deleted_at IS NULL;
            END;
            """.trimIndent()
        )
    }
}
