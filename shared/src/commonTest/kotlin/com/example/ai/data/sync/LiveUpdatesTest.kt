package com.example.ai.data.sync

import com.example.ai.api.ServerEvent
import com.example.ai.testing.FakeAppForeground
import com.example.ai.testing.FakeTodoEvents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Virtual time and fakes: a scripted event stream, a foreground switch, and recorders in place of the
 * scheduler, so no database and no network are involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveUpdatesTest {

    private class Fixture(private val testScope: TestScope, foreground: Boolean = true) {
        val events = FakeTodoEvents { testScope.currentTime }
        val foreground = FakeAppForeground(foreground)

        /** What the stored sync cursor says; a test moves it like a finished pull would. */
        var cursor = SyncCursor(serverId = "srv", revision = 0)
        var cursorFailure: Exception? = null

        /** Virtual times at which the scheduler was asked to sync / told the server is reachable. */
        val syncRequests = mutableListOf<Long>()
        val reachableSignals = mutableListOf<Long>()
        val logs = mutableListOf<String>()

        val liveUpdates = LiveUpdates(
            events = events,
            foreground = this.foreground,
            readCursor = { cursorFailure?.let { throw it }; cursor },
            requestSync = { syncRequests += testScope.currentTime },
            serverIsReachable = { reachableSignals += testScope.currentTime },
            scope = testScope.backgroundScope,
            log = { logs += it },
        )

        val connectTimes: List<Long> get() = events.connections.map { it.openedAt }
        val current get() = events.connections.last()
    }

    private fun TestScope.fixture(foreground: Boolean = true) = Fixture(this, foreground)

    private fun TestScope.passTime(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    // ---- what an event does -------------------------------------------------------------------

    @Test
    fun helloTellsTheSchedulerTheServerIsReachableAndDoesNotJustRequestASync() = runTest {
        val f = fixture()

        f.liveUpdates.start()
        runCurrent()
        f.current.send(ServerEvent.Hello("srv", 0))
        runCurrent()

        assertEquals(listOf(0L), f.reachableSignals)
        assertEquals(emptyList(), f.syncRequests, "hello goes through serverIsReachable, which already syncs now")
    }

    @Test
    fun changedAboveTheCursorRequestsASync() = runTest {
        val f = fixture()
        f.cursor = SyncCursor("srv", 5)
        f.liveUpdates.start()
        runCurrent()

        f.current.send(ServerEvent.Hello("srv", 5))
        f.current.send(ServerEvent.Changed(6))
        runCurrent()

        assertEquals(listOf(0L), f.syncRequests)
    }

    @Test
    fun changedAtOrBelowTheCursorDoesNotSync() = runTest {
        val f = fixture()
        f.cursor = SyncCursor("srv", 5)
        f.liveUpdates.start()
        runCurrent()

        f.current.send(ServerEvent.Hello("srv", 5))
        f.current.send(ServerEvent.Changed(5)) // exactly the cursor: already pulled
        f.current.send(ServerEvent.Changed(4)) // older than the cursor
        runCurrent()

        assertEquals(emptyList(), f.syncRequests)
    }

    @Test
    fun theCursorIsReadForEveryEventSoTheEchoOfOurOwnPushIsSkippedAndTheNextRealChangeIsNot() = runTest {
        val f = fixture()
        f.cursor = SyncCursor("srv", 5)
        f.liveUpdates.start()
        runCurrent()
        f.current.send(ServerEvent.Hello("srv", 5))

        f.current.send(ServerEvent.Changed(6)) // news: another device
        runCurrent()
        assertEquals(1, f.syncRequests.size)

        f.cursor = SyncCursor("srv", 6) // the sync it caused has pulled revision 6
        f.current.send(ServerEvent.Changed(6)) // the echo of the same revision again
        runCurrent()
        assertEquals(1, f.syncRequests.size, "known now")

        f.current.send(ServerEvent.Changed(7))
        runCurrent()
        assertEquals(2, f.syncRequests.size)
    }

    @Test
    fun changedFromADifferentServerSyncsEvenWhenItsRevisionIsLowerThanTheCursor() = runTest {
        // The server restarted: its counter starts again at 1, while our cursor still says 50.
        val f = fixture()
        f.cursor = SyncCursor("old-server", 50)
        f.liveUpdates.start()
        runCurrent()

        f.current.send(ServerEvent.Hello("new-server", 2))
        f.current.send(ServerEvent.Changed(3))
        runCurrent()

        assertEquals(listOf(0L), f.syncRequests, "3 is below 50, but it is a revision of another server")
    }

    @Test
    fun aClientThatNeverSyncedHasNoServerAndSyncsOnTheFirstChange() = runTest {
        val f = fixture()
        f.cursor = SyncCursor(serverId = null, revision = 0)
        f.liveUpdates.start()
        runCurrent()

        f.current.send(ServerEvent.Hello("srv", 1))
        f.current.send(ServerEvent.Changed(1))
        runCurrent()

        assertEquals(1, f.syncRequests.size)
    }

    @Test
    fun changedBeforeAnyHelloCannotBeComparedAndSyncs() = runTest {
        val f = fixture()
        f.cursor = SyncCursor("srv", 10)
        f.liveUpdates.start()
        runCurrent()

        f.current.send(ServerEvent.Changed(1)) // no hello yet: we do not know which server this is

        runCurrent()
        assertEquals(1, f.syncRequests.size)
    }

    @Test
    fun aCursorThatCannotBeReadMakesItSyncRatherThanSkip() = runTest {
        val f = fixture()
        f.cursorFailure = IllegalStateException("database is closed")
        f.liveUpdates.start()
        runCurrent()

        f.current.send(ServerEvent.Hello("srv", 0))
        f.current.send(ServerEvent.Changed(1))
        runCurrent()

        assertEquals(1, f.syncRequests.size)
        assertEquals(1, f.events.connections.size, "and a failing cursor read does not even drop the connection")
    }

    // ---- foreground ---------------------------------------------------------------------------

    @Test
    fun nothingConnectsInTheBackgroundAndStartingItDoesNotConnectEither() = runTest {
        val f = fixture(foreground = false)
        assertEquals(0, f.events.connections.size, "constructing it connects nothing")

        f.liveUpdates.start()
        passTime(60_000)

        assertEquals(0, f.events.connections.size)
    }

    @Test
    fun cameToTheForegroundConnectsAndStartingTwiceStillMeansOneConnection() = runTest {
        val f = fixture(foreground = false)
        f.liveUpdates.start()
        f.liveUpdates.start()
        runCurrent()

        f.foreground.set(true)
        passTime(5_000)

        assertEquals(1, f.events.connections.size)
        assertEquals(1, f.events.activeConnections)
    }

    @Test
    fun goingToTheBackgroundCancelsTheStreamAndComingBackReconnectsAtOnce() = runTest {
        val f = fixture()
        f.liveUpdates.start()
        runCurrent()
        f.current.send(ServerEvent.Hello("srv", 0))
        passTime(10_000)
        assertEquals(1, f.events.activeConnections)

        f.foreground.set(false)
        runCurrent()
        assertEquals(0, f.events.activeConnections, "the collector was cancelled, the connection is gone")
        passTime(60_000)
        assertEquals(1, f.events.connections.size, "and nothing reconnects in the background")

        f.foreground.set(true)
        runCurrent()
        assertEquals(listOf(0L, 70_000L), f.connectTimes, "back in the foreground: reconnects right away")
        assertEquals(1, f.events.activeConnections)

        f.current.send(ServerEvent.Hello("srv", 0))
        runCurrent()
        assertEquals(listOf(0L, 70_000L), f.reachableSignals, "the second hello is what makes the scheduler catch up")
    }

    @Test
    fun backgroundingWhileWaitingToReconnectCancelsTheReconnectAndForgetsTheBackoff() = runTest {
        val f = fixture()
        f.events.onConnect = { it.fail() }
        f.liveUpdates.start()
        passTime(2_500) // failures at 0 and 1000, the next attempt would be at 3000
        assertEquals(listOf(0L, 1_000L), f.connectTimes)

        f.foreground.set(false)
        passTime(10_000)
        assertEquals(listOf(0L, 1_000L), f.connectTimes, "no attempt in the background")

        f.foreground.set(true)
        passTime(1_500)
        // A fresh start: immediately, and the backoff is back at 1 s, not at the 4 s it would have been.
        assertEquals(listOf(0L, 1_000L, 12_500L, 13_500L), f.connectTimes)
    }

    // ---- reconnecting -------------------------------------------------------------------------

    @Test
    fun aFailingStreamIsRetriedWithExponentialBackoffCappedAtTenSeconds() = runTest {
        val f = fixture()
        f.events.onConnect = { it.fail() }

        f.liveUpdates.start()
        passTime(50_000) // attempts at 0, 1, 3, 7, 15, 25, 35 and 45 s

        val gaps = f.connectTimes.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L, 10_000L), gaps)
        assertTrue(f.logs.all { "reconnecting" in it }, "every retry says so in the log: ${f.logs}")
    }

    @Test
    fun aStreamTheServerClosesNormallyIsReconnectedToo() = runTest {
        val f = fixture()
        f.events.onConnect = { it.close() }

        f.liveUpdates.start()
        passTime(2_500)

        assertEquals(listOf(0L, 1_000L), f.connectTimes, "ending is not staying connected")
    }

    @Test
    fun aHelloResetsTheBackoffToOneSecond() = runTest {
        val f = fixture()
        f.events.onConnect = { connection ->
            when (connection.number) {
                in 1..3 -> connection.fail() // 1 s, 2 s, 4 s of waiting
                4 -> {
                    connection.send(ServerEvent.Hello("srv", 0)) // connected for real ...
                    connection.fail() // ... and then lost
                }
                else -> connection.fail()
            }
        }

        f.liveUpdates.start()
        passTime(30_000)

        val gaps = f.connectTimes.zipWithNext { a, b -> b - a }
        // Without the reset the gap after the 4th connection would be 8 s.
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 1_000L, 2_000L, 4_000L), gaps.take(6))
    }

    @Test
    fun withoutAHelloTheBackoffKeepsGrowingWhateverElseArrives() = runTest {
        val f = fixture()
        f.cursor = SyncCursor("srv", 100)
        f.events.onConnect = { connection ->
            connection.send(ServerEvent.Changed(1)) // events, but never a hello: not "connected"
            connection.fail()
        }

        f.liveUpdates.start()
        passTime(8_000)

        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), f.connectTimes)
    }

    @Test
    fun aServerThatSaysHelloAndDropsEveryConnectionIsRetriedEverySecond() = runTest {
        // Documents the decision "no minimum uptime": cheap attempts, no extra logic.
        val f = fixture()
        f.events.onConnect = { connection ->
            connection.send(ServerEvent.Hello("srv", 0))
            connection.fail()
        }

        f.liveUpdates.start()
        passTime(4_500)

        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L), f.connectTimes)
        assertEquals(5, f.reachableSignals.size)
    }

    @Test
    fun theReconnectBackoffIsTheSharedBackoffFunctionWithATenSecondCap() {
        assertEquals(10_000L, LiveUpdates.MAX_RECONNECT_BACKOFF_MILLIS)
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L),
            (1..5).map { SyncScheduler.backoffMillis(it, LiveUpdates.MAX_RECONNECT_BACKOFF_MILLIS) },
        )
    }
}
