package com.example.aiinbox.sync

sealed interface FsSyncState {
    object Idle : FsSyncState
    object Running : FsSyncState
    data class Error(val message: String?) : FsSyncState
}
