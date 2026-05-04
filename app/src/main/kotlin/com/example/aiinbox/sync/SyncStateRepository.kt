package com.example.aiinbox.sync

import com.example.aiinbox.data.db.SyncStateDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for sync UI state.
 *
 * - [runtime] is the in-memory `Idle / Running / Error` flag flipped by
 *   [SyncWorker] as a sync run progresses.
 * - [accountEmail] and [lastFullSyncAt] derive from the persistent
 *   `sync_state` table — they survive process death.
 */
@Singleton
class SyncStateRepository @Inject constructor(
    private val syncStateDao: SyncStateDao,
) {
    private val _runtime = MutableStateFlow<SyncState>(SyncState.Idle)
    val runtime: StateFlow<SyncState> = _runtime

    val accountEmail: Flow<String?> = syncStateDao.observe().map { it?.accountEmail }
    val lastFullSyncAt: Flow<Long?> = syncStateDao.observe().map { it?.lastFullSyncAt }

    fun setRunning() { _runtime.value = SyncState.Running }
    fun setIdle() { _runtime.value = SyncState.Idle }
    fun setError(cause: SyncState.Cause, message: String?) {
        _runtime.value = SyncState.Error(cause, message)
    }
}
