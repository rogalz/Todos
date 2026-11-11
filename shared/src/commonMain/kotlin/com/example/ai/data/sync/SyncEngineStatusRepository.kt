package com.example.ai.data.sync

import com.example.ai.domain.SyncOverview
import com.example.ai.domain.SyncStatusRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.scan

/**
 * Reduces the three things the data layer knows (what the engine is doing, how many rows wait, whether
 * the live stream is up) to the one [SyncOverview] the UI shows. Read-only: it starts and changes nothing.
 *
 * ## The rules
 *
 * - **Offline** when the last sync failed, or the live stream dropped after having connected. The count
 *   is the rows still `PENDING` (possibly 0).
 * - Otherwise **Syncing** while the engine runs, else **Synced**.
 *
 * ## Edge cases
 *
 * - **A retry is not "Syncing".** After a failure the engine runs again, and against a server that hangs
 *   (paused, firewalled) that attempt takes a full request timeout. Showing "Syncing" during it would
 *   make the chip claim progress that is not happening, so the failure is remembered until a sync
 *   succeeds. That is why [SyncState.Syncing] alone does not decide anything.
 * - **Cancelled sync**: the engine reports `Idle` (not `Failed`), so a cancelled sync never reads as offline.
 * - **Start-up**: [LiveConnection.Unknown] is not "offline". The stream needs a moment to connect and the
 *   chip must not flash "Offline" at every launch.
 * - **Pending rows while idle and connected** (the debounce window between a local write and its sync)
 *   still read as `Synced`: the todo's own "not synced yet" icon says it, and calling this window
 *   "Syncing" would flash the chip on every checkbox tap.
 *
 * Takes plain flows (like [SyncScheduler] takes `pendingCount`) so the rules are testable without an
 * engine, a database or a network.
 */
class SyncEngineStatusRepository(
    private val engineState: Flow<SyncState>,
    private val pendingCount: Flow<Int>,
    private val connection: Flow<LiveConnection>,
) : SyncStatusRepository {

    private data class Engine(val running: Boolean, val failing: Boolean)

    override fun observeOverview(): Flow<SyncOverview> {
        val engine = engineState.scan(Engine(running = false, failing = false)) { previous, state ->
            when (state) {
                SyncState.Idle -> Engine(running = false, failing = false)
                // Keeps `failing`: see "A retry is not Syncing".
                SyncState.Syncing -> Engine(running = true, failing = previous.failing)
                is SyncState.Failed -> Engine(running = false, failing = true)
            }
        }
        return combine(engine, pendingCount, connection) { engine, pending, connection ->
            when {
                engine.failing || connection == LiveConnection.Disconnected -> SyncOverview.Offline(pending)
                engine.running -> SyncOverview.Syncing
                else -> SyncOverview.Synced
            }
        }.distinctUntilChanged()
    }
}
