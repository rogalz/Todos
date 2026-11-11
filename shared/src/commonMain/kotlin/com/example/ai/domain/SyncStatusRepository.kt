package com.example.ai.domain

import kotlinx.coroutines.flow.Flow

/** The one-glance answer to "did my changes reach the server?", as shown by the list's status chip. */
sealed interface SyncOverview {
    /** Nothing is known to be wrong and no sync is running. */
    data object Synced : SyncOverview

    data object Syncing : SyncOverview

    /** The server cannot be reached; [pendingCount] local changes are waiting for it (possibly 0). */
    data class Offline(val pendingCount: Int) : SyncOverview
}

/**
 * Read-only view of the sync for the UI. It exists so the UI can show the state without ever touching
 * the sync engine or the network, which stay in `data`.
 */
interface SyncStatusRepository {
    /** Emits the current overview on collection and every change after it; never completes. */
    fun observeOverview(): Flow<SyncOverview>
}
