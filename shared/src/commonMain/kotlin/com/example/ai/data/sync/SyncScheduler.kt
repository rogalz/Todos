package com.example.ai.data.sync

import com.example.ai.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
 *    first success. This also covers "nothing pending but the last pull failed";
 *  - when [requestSync] is called (live updates: the server says something changed);
 *  - when [serverIsReachable] is called (live updates: the stream just connected), see below.
 *
 * ## One worker, one trigger channel
 *
 * Every reason to sync only puts a token into a *conflated* channel; a single worker takes tokens
 * one by one and runs [sync]. So two syncs never run at once, a burst of reasons while a sync is
 * running collapses into exactly one follow-up run, and an incoming reason never cancels a running
 * sync (that is why this is not `collectLatest`).
 *
 * ## Two ways in from outside
 *
 * [requestSync] is "please sync": it is one more token, so it behaves exactly like a local write: it
 * is conflated (a burst of requests never becomes a queue of syncs: at most one running and one
 * queued) and it waits out a running backoff.
 *
 * [serverIsReachable] is "the server just answered us": a failure backoff exists only to avoid
 * hammering a server that is down, and that reason is gone, so it forgets the backoff (the next
 * failure starts again at 1 s) and syncs now. A call counts for a failing sync only if it came in
 * *while that sync was running or later*: then the server was reachable after the sync started, and
 * waiting is pointless. A call from *before* the sync started must not cut anything short, that sync
 * failed after the server had proved itself reachable, so the failure is real. A call while nothing
 * is failing just wakes the worker.
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

    // A field (not created in start()) so that requestSync() can be called from outside, and a token
    // that arrives before start() simply waits for the worker.
    private val wakeUps = Channel<Unit>(Channel.CONFLATED)

    // Counts serverIsReachable() calls; the worker compares it with the value it saw when its sync
    // started (see "Two ways in from outside"). A counter, not a flag, so there is nothing to reset
    // and so no stale "reachable" can shorten a later backoff.
    private val reachableCalls = MutableStateFlow(0)

    /** Starts watching and syncing. Calling it again does nothing. */
    fun start() {
        if (worker != null) return
        worker = scope.launch {
            launch { watchPendingRows() }
            wakeUps.trySend(Unit) // the sync on start

            var consecutiveFailures = 0
            for (ignored in wakeUps) {
                val reachableCallsAtStart = reachableCalls.value
                val result = runSuspendCatching { sync() }.getOrElse { SyncResult.Failure(it) }
                if (result is SyncResult.Failure) {
                    consecutiveFailures += 1
                    // Refused rows cannot be fixed by a retry anywhere, so waking WorkManager for them
                    // every 30 s would only burn battery; the in-app retry below still goes on.
                    if (!result.onlyRejectionsLeft) askFallback()
                    if (waitOutBackoff(consecutiveFailures, reachableCallsAtStart)) consecutiveFailures = 0
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

    /** Asks for a sync. Conflated, and it waits out a running failure backoff; see the class comment. */
    fun requestSync() {
        wakeUps.trySend(Unit)
    }

    /**
     * Tells the scheduler that the server just proved it is reachable: forget the failure backoff and
     * sync now. See the class comment for exactly which backoffs this cuts short.
     */
    fun serverIsReachable() {
        // The count first, then the token: the worker that wakes for the token must already see it.
        reachableCalls.update { it + 1 }
        wakeUps.trySend(Unit)
    }

    /** @return `true` when [serverIsReachable] cut the wait short, `false` when the whole backoff passed. */
    private suspend fun waitOutBackoff(failures: Int, reachableCallsAtStart: Int): Boolean =
        withTimeoutOrNull(backoffMillis(failures)) { reachableCalls.first { it != reachableCallsAtStart } } != null

    private fun askFallback() {
        try {
            fallback?.scheduleSyncWhenOnline()
        } catch (e: Exception) {
            // The in-app retry above does not depend on the fallback, so a broken fallback is only logged.
            println("SyncScheduler: the background sync fallback failed: $e")
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watchPendingRows() {
        pendingCount
            .debounce(debounceMillis)
            .filter { it > 0 }
            .collect { wakeUps.trySend(Unit) }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 500L
        private const val FIRST_BACKOFF_MILLIS = 1_000L
        private const val MAX_BACKOFF_MILLIS = 30_000L

        /**
         * 1 s, 2 s, 4 s, 8 s, 16 s, then [maxMillis] (30 s by default) for every further failure.
         * [failures] counts from 1. The one backoff function of the app: the live-updates reconnect
         * uses it with a lower cap, so the two cannot drift apart.
         */
        internal fun backoffMillis(failures: Int, maxMillis: Long = MAX_BACKOFF_MILLIS): Long {
            // The exponent is capped before shifting so a long outage cannot overflow the Long.
            val exponent = (failures - 1).coerceIn(0, 10)
            return (FIRST_BACKOFF_MILLIS shl exponent).coerceAtMost(maxMillis)
        }
    }
}
