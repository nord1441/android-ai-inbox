package com.example.aiinbox.sync

/**
 * Runtime state of the Drive sync subsystem, surfaced to the Settings UI
 * via [SyncStateRepository.runtime]. Distinct from the persistent state
 * in `sync_state` (account email, last full-sync timestamp, manifest ETag).
 */
sealed interface SyncState {
    object Idle : SyncState
    object Running : SyncState
    data class Error(val cause: Cause, val message: String?) : SyncState

    enum class Cause { ReauthRequired, NetworkUnavailable, QuotaExceeded, Other }
}
