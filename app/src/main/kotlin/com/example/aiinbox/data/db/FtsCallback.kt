package com.example.aiinbox.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

object FtsCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        createFtsTable(db)
        createTriggers(db)
    }

    private fun createFtsTable(db: SupportSQLiteDatabase) {
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
                tokenize='trigram'
            )
            """.trimIndent()
        )
    }

    private fun createTriggers(db: SupportSQLiteDatabase) {
        // INSERT trigger
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS inbox_items_ai AFTER INSERT ON inbox_items BEGIN
                INSERT INTO inbox_fts(id, title, summary, original_text, tags, people, places)
                VALUES (new.id,
                        coalesce(new.title, ''),
                        coalesce(new.summary, ''),
                        new.original_text,
                        coalesce(new.tags, ''),
                        coalesce(new.people, ''),
                        coalesce(new.places, ''));
            END;
            """.trimIndent()
        )
        // DELETE trigger
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS inbox_items_ad AFTER DELETE ON inbox_items BEGIN
                DELETE FROM inbox_fts WHERE id = old.id;
            END;
            """.trimIndent()
        )
        // UPDATE trigger
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS inbox_items_au AFTER UPDATE ON inbox_items BEGIN
                DELETE FROM inbox_fts WHERE id = old.id;
                INSERT INTO inbox_fts(id, title, summary, original_text, tags, people, places)
                VALUES (new.id,
                        coalesce(new.title, ''),
                        coalesce(new.summary, ''),
                        new.original_text,
                        coalesce(new.tags, ''),
                        coalesce(new.people, ''),
                        coalesce(new.places, ''));
            END;
            """.trimIndent()
        )
    }
}
