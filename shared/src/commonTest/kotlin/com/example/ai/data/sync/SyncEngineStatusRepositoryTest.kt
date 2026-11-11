package com.example.ai.data.sync

import com.example.ai.domain.SyncOverview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The reduction rules of [SyncEngineStatusRepository]; the three inputs are plain flows the test sets. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineStatusRepositoryTest {

    private class Fixture(scope: TestScope) {
        val engine = MutableStateFlow<SyncState>(SyncState.Idle)
        val pending = MutableStateFlow(0)
        val connection = MutableStateFlow(LiveConnection.Unknown)

        /** Everything the chip would have been told, in order. */
        val seen = mutableListOf<SyncOverview>()

        init {
            val repository = SyncEngineStatusRepository(engine, pending, connection)
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                repository.observeOverview().collect { seen += it }
            }
        }

        val now: SyncOverview get() = seen.last()
    }

    private val failed = SyncState.Failed(Instant.fromEpochMilliseconds(0))

    @Test
    fun aQuietAppWithNothingPendingIsSynced() = runTest {
        val f = Fixture(this)

        assertEquals(SyncOverview.Synced, f.now)
    }

    @Test
    fun aRunningSyncIsSyncing() = runTest {
        val f = Fixture(this)

        f.engine.value = SyncState.Syncing

        assertEquals(SyncOverview.Syncing, f.now)
    }

    @Test
    fun aFailedSyncIsOfflineWithTheNumberOfWaitingChanges() = runTest {
        val f = Fixture(this)
        f.connection.value = LiveConnection.Connected
        f.pending.value = 2

        f.engine.value = SyncState.Failed(Instant.fromEpochMilliseconds(5))

        assertEquals(SyncOverview.Offline(2), f.now)
    }

    @Test
    fun theWaitingCountFollowsWhileOffline() = runTest {
        val f = Fixture(this)
        f.engine.value = failed

        f.pending.value = 1
        assertEquals(SyncOverview.Offline(1), f.now)
        f.pending.value = 3
        assertEquals(SyncOverview.Offline(3), f.now)
    }

    @Test
    fun aDroppedStreamIsOfflineEvenWithNothingPending() = runTest {
        val f = Fixture(this)
        f.connection.value = LiveConnection.Connected

        f.connection.value = LiveConnection.Disconnected

        assertEquals(SyncOverview.Offline(0), f.now)
    }

    @Test
    fun aRetryAfterAFailureStaysOfflineInsteadOfFlickeringToSyncing() = runTest {
        // Against a server that hangs, the retry takes a whole request timeout; "Syncing" then would be a lie.
        val f = Fixture(this)
        f.pending.value = 1
        f.engine.value = failed

        f.engine.value = SyncState.Syncing

        assertEquals(SyncOverview.Offline(1), f.now)
    }

    @Test
    fun theFirstSuccessAfterAFailureIsSyncedAgain() = runTest {
        val f = Fixture(this)
        f.connection.value = LiveConnection.Connected
        f.pending.value = 1
        f.engine.value = failed
        f.engine.value = SyncState.Syncing

        f.pending.value = 0
        f.engine.value = SyncState.Idle

        assertEquals(SyncOverview.Synced, f.now)
    }

    @Test
    fun aCancelledSyncEndsAsIdleAndNeverLooksOffline() = runTest {
        val f = Fixture(this)
        f.engine.value = SyncState.Syncing

        f.engine.value = SyncState.Idle // what SyncEngine reports for a cancellation

        assertEquals(SyncOverview.Synced, f.now)
    }

    @Test
    fun aStreamThatHasNotConnectedYetIsNotOffline() = runTest {
        // At every launch the stream needs a moment to connect.
        val f = Fixture(this)

        f.pending.value = 1
        f.engine.value = SyncState.Syncing
        f.engine.value = SyncState.Idle

        assertEquals(LiveConnection.Unknown, f.connection.value)
        assertEquals(SyncOverview.Synced, f.now)
    }

    @Test
    fun pendingRowsWhileIdleAndConnectedStillReadAsSynced() = runTest {
        // The debounce window between a local write and its sync: the todo's own icon says "not synced".
        val f = Fixture(this)
        f.connection.value = LiveConnection.Connected

        f.pending.value = 2

        assertEquals(SyncOverview.Synced, f.now)
    }

    @Test
    fun reconnectingBringsItBackFromOffline() = runTest {
        val f = Fixture(this)
        f.connection.value = LiveConnection.Disconnected
        assertEquals(SyncOverview.Offline(0), f.now)

        f.connection.value = LiveConnection.Connected

        assertEquals(SyncOverview.Synced, f.now)
    }

    @Test
    fun repeatedIdenticalOverviewsAreNotEmittedAgain() = runTest {
        val f = Fixture(this)

        f.pending.value = 1 // Synced -> Synced
        f.pending.value = 2
        f.connection.value = LiveConnection.Connected

        assertEquals(listOf<SyncOverview>(SyncOverview.Synced), f.seen)
    }
}
