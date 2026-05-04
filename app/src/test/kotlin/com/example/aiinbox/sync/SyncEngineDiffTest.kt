package com.example.aiinbox.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncEngineDiffTest {

    private fun localRef(id: String, updated: Long, deleted: Long? = null, synced: Long? = null) =
        SyncEngine.LocalRef(id, updated, deleted, synced)

    private fun remoteItem(id: String, updated: Long, deleted: Long? = null) =
        SyncManifest.ManifestItem(id, updated, deleted, emptyList(), emptyList())

    @Test
    fun localOnly_isPushed() {
        val d = SyncEngine.diff(listOf(localRef("a", 100)), emptyList())
        assertEquals(listOf("a"), d.push)
        assertEquals(emptyList<String>(), d.pull)
    }

    @Test
    fun remoteOnly_isPulled() {
        val d = SyncEngine.diff(emptyList(), listOf(remoteItem("a", 100)))
        assertEquals(listOf("a"), d.pull)
        assertEquals(emptyList<String>(), d.push)
    }

    @Test
    fun localNewer_isPushed() {
        val d = SyncEngine.diff(
            local = listOf(localRef("a", 200)),
            remote = listOf(remoteItem("a", 100)),
        )
        assertEquals(listOf("a"), d.push)
    }

    @Test
    fun remoteNewer_isPulled() {
        val d = SyncEngine.diff(
            local = listOf(localRef("a", 100)),
            remote = listOf(remoteItem("a", 200)),
        )
        assertEquals(listOf("a"), d.pull)
    }

    @Test
    fun localDeletedMoreRecentlyThanRemoteUpdated_isPushed() {
        val d = SyncEngine.diff(
            local = listOf(localRef("a", 100, deleted = 300)),
            remote = listOf(remoteItem("a", 200)),
        )
        assertEquals(listOf("a"), d.push)
    }

    @Test
    fun remoteDeletedMoreRecentlyThanLocalUpdated_isPulled() {
        val d = SyncEngine.diff(
            local = listOf(localRef("a", 200)),
            remote = listOf(remoteItem("a", 100, deleted = 300)),
        )
        assertEquals(listOf("a"), d.pull)
    }

    @Test
    fun equalTimestamps_skip() {
        val d = SyncEngine.diff(
            local = listOf(localRef("a", 100)),
            remote = listOf(remoteItem("a", 100)),
        )
        assertEquals(listOf("a"), d.skip)
    }
}
