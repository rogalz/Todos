package com.example.ai.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.domain.SyncOverview
import com.example.ai.domain.SyncStatusRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * State of the list's status chip. A ViewModel of its own (not part of the list's) because it has a
 * different source, lifetime and rule, and the list's ViewModel and tests stay untouched by it.
 *
 * [SyncOverview.Syncing] is shown only if a sync lasts longer than [syncingGrace]: most syncs finish in
 * a few milliseconds, and a chip that flashes "Syncing…" on every checkbox tap is noise. Everything else,
 * including "Offline", is shown at once.
 */
class SyncStatusViewModel(
    repository: SyncStatusRepository,
    syncingGrace: Duration = SYNCING_GRACE,
) : ViewModel() {

    val overview: StateFlow<SyncOverview> = repository.observeOverview()
        .withSyncingGrace(syncingGrace)
        // Calm until the repository has said anything; its first value follows within milliseconds.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SyncOverview.Synced)

    companion object {
        /** Spec: "Syncing… shown only if syncing lasts > 300 ms". */
        val SYNCING_GRACE: Duration = 300.milliseconds
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Holds a [SyncOverview.Syncing] back for [grace]. If something else arrives within that time (the sync
 * ended) the held value is dropped before anyone saw it, so a short sync is never displayed, and what the
 * chip showed before simply stays. `transformLatest` is what cancels the wait; any other value is
 * passed on immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<SyncOverview>.withSyncingGrace(grace: Duration): Flow<SyncOverview> =
    transformLatest { overview ->
        if (overview is SyncOverview.Syncing) delay(grace)
        emit(overview)
    }
