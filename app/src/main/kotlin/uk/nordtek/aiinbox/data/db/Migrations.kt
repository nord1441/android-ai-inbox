package uk.nordtek.aiinbox.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * **マイグレーションポリシー (pre-release):**
 *
 * v2 → v3 以降の `Migration` は意図的に書かれていない。本アプリは個人開発の
 * pre-release 段階で、ユーザは開発者本人のみ。スキーマ変更時の復旧手段は
 * 「アプリを再インストール」とする方針が確認済み。
 *
 * 一般公開フェーズに入った時点で、本ファイルに `MIGRATION_2_3` 以降を
 * 追加しつつ `AppDatabase` の `fallbackToDestructiveMigration` を見直すこと。
 */

/**
 * v1 → v2 マイグレーション。
 *
 * 変更内容:
 *  1. inbox_items.original_text を NOT NULL → NULL 許可（テーブル再作成）
 *  2. attachments テーブル新設（外部キー CASCADE）
 *  3. inbox_fts に ocr_text 列を追加（テーブル再作成 + トリガ再作成）
 *  4. attachments の INSERT/UPDATE/DELETE トリガを追加（FTS 行を再構築）
 */
/**
 * v1 → v2 マイグレーション。詳細は [migrate] 内コメント参照。
 *
 * **マイグレーションテストでの注意:**
 * `MigrationTestHelper.runMigrationsAndValidate(...)` を呼ぶ際は必ず
 * `validateDroppedTables = false` を指定すること。理由は以下:
 * - `inbox_fts` は FTS5 仮想テーブルで `FtsCallback` がランタイム生成する
 * - Room の `exportSchema` には仮想テーブル / トリガが含まれない
 * - `true` 指定だと「想定外のテーブルが存在する」として検証が失敗する
 *
 * 関連: [AppDatabaseMigrationTest]
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // === 1) inbox_items を再作成して original_text を NULL 許可に ===
        db.execSQL(
            """
            CREATE TABLE inbox_items_new (
                id TEXT NOT NULL PRIMARY KEY,
                original_text TEXT,
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
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO inbox_items_new
            SELECT id, original_text, original_subject, source_app, received_at, status,
                   processing_attempts, last_error, title, summary, category, tags, people,
                   places, urls, event_title, event_start_millis, event_end_millis,
                   event_location, event_confidence, user_edited_fields, updated_at
              FROM inbox_items
            """.trimIndent()
        )
        db.execSQL("DROP TABLE inbox_items")
        db.execSQL("ALTER TABLE inbox_items_new RENAME TO inbox_items")
        db.execSQL("CREATE INDEX index_inbox_items_received_at ON inbox_items(received_at)")
        db.execSQL("CREATE INDEX index_inbox_items_status ON inbox_items(status)")
        db.execSQL("CREATE INDEX index_inbox_items_category ON inbox_items(category)")

        // === 2) attachments テーブル新設 ===
        db.execSQL(
            """
            CREATE TABLE attachments (
                id TEXT NOT NULL PRIMARY KEY,
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
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX index_attachments_item_id ON attachments(item_id)")
        db.execSQL("CREATE INDEX index_attachments_item_id_ordering ON attachments(item_id, ordering)")

        // === 3) inbox_fts を再作成（ocr_text 列追加） ===
        db.execSQL("DROP TRIGGER IF EXISTS inbox_items_ai")
        db.execSQL("DROP TRIGGER IF EXISTS inbox_items_au")
        db.execSQL("DROP TRIGGER IF EXISTS inbox_items_ad")
        db.execSQL("DROP TABLE IF EXISTS inbox_fts")
        FtsCallback.createFtsTable(db)
        FtsCallback.createTriggers(db)

        // 既存 inbox_items の FTS 行を再構築（ocr_text は NULL）
        db.execSQL(
            """
            INSERT INTO inbox_fts (id, title, summary, original_text, tags, people, places, ocr_text)
            SELECT id,
                   coalesce(title, ''),
                   coalesce(summary, ''),
                   coalesce(original_text, ''),
                   coalesce(tags, ''),
                   coalesce(people, ''),
                   coalesce(places, ''),
                   ''
              FROM inbox_items
            """.trimIndent()
        )
    }
}
