package com.example.ai.data.sync

import com.example.ai.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Decides *when* to sync. Nothing else in the app calls the engine on a schedule, and nothing here
 * knows how a sync works: the scheduler only watches Room (through [pendingCount]) and calls
 * [sync]. That keeps the repository free of any sync coupling.
 *
 * A sync runs:
 *  - once on [start];
 *  - when rows are pending (a local write), after [debounceMillis] of quiet, so a burst of edits
 *    (typing, ticking several checkboxes) becomes one sync;
 *  - again, after [debounceMillis], when a sync succeeded but rows are pending again (edited while
 *    it was running);
 *  - after a failure, with exponential backoff: 1 s, 2 s, 4 s ... capped at 30 s, reset by the
 *    first success. This also covers "nothing pending but the last pull failed".
 *
 * ## One worker, one trigger channel
 *
 * Every reason to sync only puts a token into a *conflated* channel; a single worker takes tokens
 * one by one and runs [sync]. So two syncs never run at once, a burst of reasons while a sync is
 * running collapses into exactly one follow-up run, and an incoming reason never cancels a running
 * sync (that is why this is not `collectLatest`).
 *
 * ## Edge cases
 *
 * - A write during a backoff waits until the backoff is over; hammering a server that just failed
 *   would only make things worse. The write is not lost, its token is already in the channel.
 * - The pending count is debounced *before* it is filtered for "> 0": when the count falls back to
 *   0 inside the debounce window (the start-up sync just uploaded everything) no second sync runs.
 * - "Success but still pending" re-runs at the debounce pace. Normally that happens once, after an
 *   edit made during a sync. A row that could never become `SYNCED` without failing would loop at
 *   that pace, but the engine turns every such case into a failure (so: backoff), not a success.
 *
 * @param pendingCount number of rows waiting for upload; emits its current value when collected.
 * @param sync one sync; must not throw (see [SyncEngine]), but an exception is treated as a failure.
 * @param scope the app-lifetime scope the worker lives in.
 * @param fallback told after every failed sync, so the platform can retry when the app is no longer
 *   running (Android: WorkManager). A fallback that throws must not stop the scheduler.
 */
class SyncScheduler(
    private val pendingCount: Flow<Int>,
    private val sync: suspend () -> SyncResult,
    private val scope: CoroutineScope,
    private val fallback: BackgroundSyncFallback? = null,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
) {

    private var worker: Job? = null

    /** Starts watching and syncing. Calling it again does nothing. */
    fun start() {
        if (worker != null) return
        val wakeUps = Channel<Unit>(Channel.CONFLATED)
        worker = scope.launch {
            launch { watchPendingRows(wakeUps) }
            wakeUps.trySend(Unit) // the sync on start

            var consecutiveFailures = 0
            for (ignored in wakeUps) {
                val result = runSuspendCatching { sync() }.getOrElse { SyncResult.Failure(it) }
                if (result is SyncResult.Failure) {
                    consecutiveFailures += 1
                    // Refused rows cannot be fixed by a retry anywhere, so waking WorkManager for them
                    // every 30 s would only burn battery; the in-app retry below still goes on.
                    if (!result.onlyRejectionsLeft) askFallback()
                    delay(backoffMillis(consecutiveFailures))
                    wakeUps.trySend(Unit)
                } else {
                    consecutiveFailures = 0
                    if (pendingCount.first() > 0) {
                        delay(debounceMillis)
                        wakeUps.trySend(Unit)
                    }
                }
            }
        }
    }

    private fun askFallback() {
        try {
            fallback?.scheduleSyncWhenOnline()
        } catch (e: Exception) {
            // The in-app retry above does not depend on the fallback, so a broken fallback is only logged.
            println("SyncScheduler: the background sync fallback failed: $e")
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watchPendingRows(wakeUps: Channel<Unit>) {
        pendingCount
            .debounce(debounceMillis)
            .filter { it > 0 }
            .collect { wakeUps.trySend(Unit) }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 500L
        private const val FIRST_BACKOFF_MILLIS = 1_000L
        private const val MAX_BACKOFF_MILLIS = 30_000L

        /** 1 s, 2 s, 4 s, 8 s, 16 s, then 30 s for every further failure. [failures] counts from 1. */
        internal fun backoffMillis(failures: Int): Long {
            // The exponent is capped before shifting so a long outage cannot overflow the Long.
            val exponent = (failures - 1).coerceIn(0, 10)
            return (FIRST_BACKOFF_MILLIS shl exponent).coerceAtMost(MAX_BACKOFF_MILLIS)
        }
    }
}
