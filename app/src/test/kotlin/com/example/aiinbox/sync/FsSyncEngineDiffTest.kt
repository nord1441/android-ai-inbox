package com.example.aiinbox.sync

import com.example.aiinbox.data.db.InboxRefRow
import org.junit.Assert.assertEquals
import org.junit.Test

class FsSyncEngineDiffTest {

    private fun loc(id: String, deletedAt: Long? = null) = InboxRefRow(id, deletedAt, 0L)
    private fun rem(id: String, isTombstone: Boolean = false) =
        FsSyncEngine.Companion.RemoteIdRef(id, isTombstone)

    @Test
    fun localOnly_alive_isExported() {
        val d = FsSyncEngine.diff(listOf(loc("a")), emptyList())
        assertEquals(listOf("a"), d.export)
    }

    @Test
    fun localOnly_tombstoned_isExported() {
        val d = FsSyncEngine.diff(listOf(loc("a", deletedAt = 100)), emptyList())
        assertEquals(listOf("a"), d.export)  // exported as tombstone shape
    }

    @Test
    fun remoteOnly_alive_isImported() {
        val d = FsSyncEngine.diff(emptyList(), listOf(rem("a")))
        assertEquals(listOf("a"), d.import)
    }

    @Test
    fun remoteOnly_tombstoned_isNoOp() {
        val d = FsSyncEngine.diff(emptyList(), listOf(rem("a", isTombstone = true)))
        assertEquals(emptyList<String>(), d.import)
        assertEquals(emptyList<String>(), d.softDeleteLocally)
        assertEquals(emptyList<String>(), d.skip)
    }

    @Test
    fun bothAlive_isSkipped() {
        val d = FsSyncEngine.diff(listOf(loc("a")), listOf(rem("a")))
        assertEquals(listOf("a"), d.skip)
    }

    @Test
    fun bothTombstoned_isSkipped() {
        val d = FsSyncEngine.diff(listOf(loc("a", deletedAt = 100)), listOf(rem("a", isTombstone = true)))
        assertEquals(listOf("a"), d.skip)
    }

    @Test
    fun localAlive_diskTombstoned_isSoftDeletedLocally() {
        val d = FsSyncEngine.diff(listOf(loc("a")), listOf(rem("a", isTombstone = true)))
        assertEquals(listOf("a"), d.softDeleteLocally)
    }

    @Test
    fun localTombstoned_diskAlive_isReExportedAsTombstone() {
        val d = FsSyncEngine.diff(listOf(loc("a", deletedAt = 100)), listOf(rem("a")))
        assertEquals(listOf("a"), d.reExportTombstone)
    }
}
