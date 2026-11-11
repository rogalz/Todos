package com.example.ai.data.sync

import com.example.ai.data.remote.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Virtual time throughout: the scheduler runs in the test's scheduler, so "500 ms later" costs
 * nothing. A `MutableStateFlow` stands in for Room's pending count and a scripted function for the
 * engine, so these tests need no database.
 */
class SyncSchedulerTest {

    private val offline = SyncResult.Failure(Exception("offline"))

    /** A scripted sync. By default it behaves like a real one: success, and the rows are uploaded. */
    private class FakeSync(private val testScope: TestScope, private val pending: MutableStateFlow<Int>) {
        val callTimes = mutableListOf<Long>()
        val results = ArrayDeque<SyncResult>()
        var clearsPendingOnSuccess = true
        var onCall: suspend (callNumber: Int) -> Unit = {}

        suspend fun run(): SyncResult {
            callTimes += testScope.currentTime
            onCall(callTimes.size)
            val result = results.removeFirstOrNull() ?: SyncResult.Success
            if (result is SyncResult.Success && clearsPendingOnSuccess) pending.value = 0
            return result
        }
    }

    private class Fixture(
        testScope: TestScope,
        initialPending: Int = 0,
        scope: CoroutineScope = testScope.backgroundScope,
        fallback: BackgroundSyncFallback? = null,
    ) {
        val pending = MutableStateFlow(initialPending)
        val sync = FakeSync(testScope, pending)
        val scheduler = SyncScheduler(pendingCount = pending, sync = sync::run, scope = scope, fallback = fallback)
    }

    private fun TestScope.fixture(initialPending: Int = 0, fallback: BackgroundSyncFallback? = null) =
        Fixture(this, initialPending, fallback = fallback)

    private fun TestScope.passTime(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    // ---- triggers -----------------------------------------------------------------------------

    @Test
    fun syncsOnceOnStart() = runTest {
        val f = fixture()

        f.scheduler.start()
        runCurrent()

        assertEquals(listOf(0L), f.sync.callTimes)
    }

    @Test
    fun startingTwiceStillRunsOneWorker() = runTest {
        val f = fixture()

        f.scheduler.start()
        f.scheduler.start()
        passTime(5_000)

        assertEquals(listOf(0L), f.sync.callTimes)
    }

    @Test
    fun aLocalWriteTriggersASyncOnlyAfterTheDebounce() = runTest {
        val f = fixture()
        f.scheduler.start()
        runCurrent()

        f.pending.value = 1 // a local write at t = 0
        passTime(499)
        assertEquals(listOf(0L), f.sync.callTimes, "still inside the debounce window")

        passTime(2)
        assertEquals(listOf(0L, 500L), f.sync.callTimes)
    }

    @Test
    fun aBurstOfWritesBecomesOneSync() = runTest {
        val f = fixture()
        f.scheduler.start()
        runCurrent()

        for (count in 1..5) {
            f.pending.value = count
            passTime(100) // each write restarts the debounce
        }
        passTime(2_000)

        // One for the start, one for the whole burst: it fired 500 ms after the last write (made at t = 400).
        assertEquals(listOf(0L, 900L), f.sync.callTimes)
    }

    @Test
    fun noPendingRowsMeansNoSync() = runTest {
        val f = fixture()
        f.scheduler.start()
        runCurrent()

        passTime(60_000)

        assertEquals(listOf(0L), f.sync.callTimes, "only the sync on start")
    }

    @Test
    fun theCountFallingBackToZeroInsideTheDebounceWindowTriggersNothing() = runTest {
        val f = fixture()
        f.scheduler.start()
        runCurrent()

        f.pending.value = 1
        passTime(200)
        f.pending.value = 0 // something else uploaded the row meanwhile
        passTime(2_000)

        assertEquals(listOf(0L), f.sync.callTimes)
    }

    @Test
    fun rowsThatWerePendingAtStartAreUploadedByTheStartSyncAndNotAgain() = runTest {
        val f = fixture(initialPending = 3)

        f.scheduler.start()
        passTime(5_000)

        assertEquals(listOf(0L), f.sync.callTimes)
    }

    // ---- failures and backoff -----------------------------------------------------------------

    @Test
    fun afterFailuresItRetriesWithExponentialBackoffCappedAtThirtySeconds() = runTest {
        val f = fixture()
        repeat(8) { f.sync.results += offline }

        f.scheduler.start()
        passTime(200_000)

        val gaps = f.sync.callTimes.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), gaps)
        assertEquals(9, f.sync.callTimes.size, "the ninth attempt succeeded, so it stops retrying")
    }

    @Test
    fun theFirstSuccessResetsTheBackoff() = runTest {
        val f = fixture()
        f.sync.results += listOf(offline, offline, SyncResult.Success, offline, SyncResult.Success)
        f.scheduler.start()
        passTime(10_000)
        assertEquals(listOf(0L, 1_000L, 3_000L), f.sync.callTimes, "two failures: waits of 1 s and 2 s, then success")

        f.pending.value = 1 // a new write, which then fails once
        passTime(10_000)

        val gap = f.sync.callTimes[4] - f.sync.callTimes[3]
        assertEquals(1_000L, gap, "after the success the backoff starts again at 1 s, not at 4 s")
    }

    @Test
    fun aFailedSyncIsRetriedEvenWhenNothingIsPending() = runTest {
        // "the last pull failed": no rows to upload, but the server could not be reached.
        val f = fixture(initialPending = 0)
        f.sync.results += offline

        f.scheduler.start()
        passTime(1_500)

        assertEquals(listOf(0L, 1_000L), f.sync.callTimes)
    }

    @Test
    fun aWriteDuringABackoffWaitsForTheBackoffToEnd() = runTest {
        val f = fixture()
        f.sync.results += offline
        f.scheduler.start()
        runCurrent() // fails at t = 0, next attempt at t = 1000

        passTime(100)
        f.pending.value = 1 // would fire at t = 600 without a backoff
        passTime(2_000)

        assertEquals(listOf(0L, 1_000L), f.sync.callTimes, "no attempt at 600, and the write is not lost")
    }

    @Test
    fun anExceptionFromSyncIsTreatedAsAFailureAndTheWorkerSurvives() = runTest {
        val f = fixture()
        f.sync.onCall = { callNumber -> if (callNumber == 1) error("a bug in the engine") }

        f.scheduler.start()
        passTime(1_500)

        assertEquals(listOf(0L, 1_000L), f.sync.callTimes)
    }

    // ---- background fallback (Android: WorkManager) ----------------------------------------------

    @Test
    fun theFallbackIsAskedAfterEveryFailedSyncAndNeverAfterASuccess() = runTest {
        var asked = 0
        val f = fixture(fallback = { asked += 1 })
        f.sync.results += listOf(offline, offline, SyncResult.Success)

        f.scheduler.start()
        passTime(10_000)

        assertEquals(3, f.sync.callTimes.size)
        assertEquals(2, asked, "once per failure, none for the success")
    }

    @Test
    fun noFallbackIsNeededToRetry() = runTest {
        val f = fixture(fallback = null)
        f.sync.results += offline

        f.scheduler.start()
        passTime(1_500)

        assertEquals(listOf(0L, 1_000L), f.sync.callTimes)
    }

    @Test
    fun theFallbackIsNotAskedWhenOnlyRefusedRowsAreLeftButTheInAppRetryGoesOn() = runTest {
        var asked = 0
        val f = fixture(fallback = { asked += 1 })
        val refused = SyncResult.Failure(ApiException(400, "no"), rejectedIds = listOf("row"))
        f.sync.results += listOf(refused, refused)

        f.scheduler.start()
        passTime(10_000)

        assertEquals(0, asked, "a retry in the background would be refused again")
        assertEquals(listOf(0L, 1_000L, 3_000L), f.sync.callTimes, "the in-app retry with backoff is unchanged")
    }

    @Test
    fun aFallbackThatThrowsDoesNotStopTheRetries() = runTest {
        val f = fixture(fallback = { error("WorkManager is not available") })
        f.sync.results += listOf(offline, offline)

        f.scheduler.start()
        passTime(10_000)

        assertEquals(listOf(0L, 1_000L, 3_000L), f.sync.callTimes)
    }

    // ---- only refused rows left -----------------------------------------------------------------

    @Test
    fun onlyRejectionsLeftIsTrueOnlyWhenTheSyncRanToTheEndAndRowsWereRefused() {
        assertTrue(SyncResult.Failure(ApiException(400, ""), listOf("a")).onlyRejectionsLeft)
        // Stopped by a network error after one row was refused: a retry can help.
        assertFalse(SyncResult.Failure(Exception("offline"), listOf("a")).onlyRejectionsLeft)
        // Stopped by the server being in trouble.
        assertFalse(SyncResult.Failure(ApiException(503, ""), emptyList()).onlyRejectionsLeft)
        assertFalse(SyncResult.Failure(ApiException(503, ""), listOf("a")).onlyRejectionsLeft)
        // "Try again later" statuses are not refusals.
        assertFalse(SyncResult.Failure(ApiException(429, ""), listOf("a")).onlyRejectionsLeft)
        assertFalse(SyncResult.Failure(ApiException(400, ""), emptyList()).onlyRejectionsLeft)
    }

    // ---- still pending after a success --------------------------------------------------------

    @Test
    fun aSuccessThatLeavesRowsPendingRunsAgainAtTheDebouncePace() = runTest {
        // Rows edited during a sync are still pending when it succeeds.
        val f = fixture(initialPending = 1)
        f.sync.clearsPendingOnSuccess = false
        f.sync.onCall = { callNumber -> if (callNumber == 3) f.pending.value = 0 }

        f.scheduler.start()
        passTime(10_000)

        assertEquals(listOf(0L, 500L, 1_000L), f.sync.callTimes)
    }

    // ---- a running sync -----------------------------------------------------------------------

    @Test
    fun writesDuringARunningSyncNeitherCancelItNorStartAParallelOne() = runTest {
        val f = fixture()
        val release = CompletableDeferred<Unit>()
        var cancelled = false
        f.sync.onCall = { callNumber ->
            if (callNumber == 1) {
                try {
                    release.await()
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            }
        }
        f.scheduler.start()
        runCurrent() // the first sync is now running and suspended

        f.pending.value = 1
        passTime(1_000)
        f.pending.value = 2
        passTime(1_000)
        f.pending.value = 3
        passTime(1_000)

        assertFalse(cancelled, "a new write must not cancel the running sync")
        assertEquals(listOf(0L), f.sync.callTimes, "no second sync while the first one runs")

        release.complete(Unit)
        passTime(5_000)

        assertEquals(2, f.sync.callTimes.size, "all the writes together cause exactly one follow-up sync")
    }

    // ---- lifetime -----------------------------------------------------------------------------

    @Test
    fun cancellingTheScopeStopsAllSyncing() = runTest {
        val job = Job()
        val f = Fixture(this, scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job))
        f.scheduler.start()
        runCurrent()

        job.cancel()
        f.pending.value = 1
        passTime(60_000)

        assertEquals(listOf(0L), f.sync.callTimes)
    }

    // ---- backoff arithmetic -------------------------------------------------------------------

    @Test
    fun backoffDoublesFromOneSecondAndStopsAtThirtySeconds() {
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)

        assertEquals(expected, (1..7).map { SyncScheduler.backoffMillis(it) })
    }

    @Test
    fun backoffDoesNotOverflowAfterAVeryLongOutage() {
        assertEquals(30_000L, SyncScheduler.backoffMillis(1_000))
        assertEquals(30_000L, SyncScheduler.backoffMillis(Int.MAX_VALUE))
        assertTrue(SyncScheduler.backoffMillis(0) > 0, "a defensive value, never zero or negative")
    }
}
