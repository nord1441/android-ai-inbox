package uk.nordtek.aiinbox.sync

import uk.nordtek.aiinbox.data.db.FsSyncStateDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FsSyncStateRepository @Inject constructor(
    private val dao: FsSyncStateDao,
) {
    private val _runtime = MutableStateFlow<FsSyncState>(FsSyncState.Idle)
    val runtime: StateFlow<FsSyncState> = _runtime

    val folderUri: Flow<String?> = dao.observe().map { it?.folderUri }
    val lastFullSyncAt: Flow<Long?> = dao.observe().map { it?.lastFullSyncAt }

    fun setRunning() { _runtime.value = FsSyncState.Running }
    fun setIdle() { _runtime.value = FsSyncState.Idle }
    fun setError(message: String?) { _runtime.value = FsSyncState.Error(message) }
}
