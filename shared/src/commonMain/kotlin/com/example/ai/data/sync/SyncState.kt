package com.example.ai.data.sync

import kotlin.time.Instant

/** What the engine is doing right now. Nothing shows it yet; the sync indicator (stage 5) will. */
sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState

    /** The last sync did not complete; [at] is the time it ended (from the injected clock). */
    data class Failed(val at: Instant) : SyncState
}
