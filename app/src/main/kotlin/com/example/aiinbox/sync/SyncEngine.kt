package com.example.aiinbox.sync

/**
 * Pure-function diff between local DB state and a Drive-side manifest.
 *
 * "Last-Write-Wins" with tombstone awareness: each item's effective
 * timestamp is `max(updated_at, deleted_at)`, so a fresh delete on one
 * side beats an older edit on the other. Ties resolve to skip — the two
 * sides are already in agreement.
 *
 * Task 8 will widen this from an object to a class with apply-pull /
 * apply-push methods that drive the real Drive REST calls.
 */
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
