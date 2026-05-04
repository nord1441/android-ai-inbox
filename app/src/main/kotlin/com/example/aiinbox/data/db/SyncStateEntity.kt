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
