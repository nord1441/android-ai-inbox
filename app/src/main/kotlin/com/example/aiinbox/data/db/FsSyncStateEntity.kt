package com.example.aiinbox.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding cross-cutting filesystem-sync state. Persisted
 * in addition to FsSyncFolderStore (EncryptedSharedPreferences) so the
 * sync engine can read it without a Context, and so a future support
 * bundle can include the URI without a separate code path.
 */
@Entity(tableName = "fs_sync_state")
data class FsSyncStateEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "folder_uri") val folderUri: String? = null,
    @ColumnInfo(name = "last_full_sync_at") val lastFullSyncAt: Long? = null,
)
