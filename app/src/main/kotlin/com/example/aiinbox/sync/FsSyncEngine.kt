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
