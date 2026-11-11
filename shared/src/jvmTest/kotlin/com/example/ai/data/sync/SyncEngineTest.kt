package com.example.ai.data.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.ai.api.TodoDto
import com.example.ai.data.RoomTodoRepository
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.local.SyncStatus
import com.example.ai.data.local.TodoEntity
import com.example.ai.data.remote.ApiException
import com.example.ai.testing.FakeClock
import com.example.ai.testing.FakeTodoApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The engine against a real in-memory Room and a [FakeTodoApi]. The fake server's hooks let a test
 * hold a push or a pull "in flight" and edit the database meanwhile, which is how the races are
 * reproduced.
 */
class SyncEngineTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: RoomTodoRepository
    private lateinit var store: SyncStore
    private lateinit var engine: SyncEngine
    private val clock = FakeClock(startMillis = 10_000)
    private val api = FakeTodoApi()
    private val logs = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<AppDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        repository = RoomTodoRepository(database.todoDao(), clock)
        store = SyncStore(database, database.syncDao())
        engine = SyncEngine(api, store, clock, log = { logs += it })
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    /** Creating moves the clock first, so every todo and every edit gets its own updatedAt. */
    private suspend fun createTodo(title: String): String {
        clock.advanceBy(10)
        return repository.createTodo(title, description = "")
    }

    private suspend fun row(id: String): TodoEntity? = database.syncDao().getAnyById(id)

    private suspend fun pendingIds(): Set<String> = database.syncDao().getPending().map { it.id }.toSet()

    private fun remoteVersion(id: String, title: String, updatedAt: Long, deletedAt: Long? = null) =
        TodoDto(id, title, description = "", isDone = false, createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt)

    // ---- push ---------------------------------------------------------------------------------

    @Test
    fun pendingRowIsPushedAndBecomesSynced() = runTest {
        val id = createTodo("Buy milk")

        val result = engine.sync()

        assertEquals(SyncResult.Success, result)
        assertEquals(SyncStatus.SYNCED, row(id)?.syncStatus)
        assertEquals("Buy milk", api.stored.getValue(id).title)
        assertEquals(emptySet(), pendingIds())
    }

    @Test
    fun deletedRowIsPushedAsATombstoneAndStaysASyncedTombstone() = runTest {
        val id = createTodo("Gone soon")
        engine.sync()
        clock.advanceBy(10)
        repository.deleteTodo(id)

        val result = engine.sync()

        assertEquals(SyncResult.Success, result)
        assertTrue(api.stored.getValue(id).deletedAt != null, "the server must have received the tombstone")
        assertEquals(SyncStatus.SYNCED, row(id)?.syncStatus)
        assertTrue(row(id)?.deletedAt != null)
        assertEquals(emptyList(), repository.observeTodos().first())
    }

    @Test
    fun rowsAreUploadedOldestChangeFirst() = runTest {
        val first = createTodo("first")
        val second = createTodo("second")
        clock.advanceBy(10)
        repository.setDone(first, true) // now the *oldest created* row has the newest change

        engine.sync()

        assertEquals(listOf(second, first), api.pushes.map { it.id })
    }

    @Test
    fun editMadeWhileThePushIsInFlightStaysPendingAndTheNextSyncPushesIt() = runTest {
        val id = createTodo("original")
        val pushStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.onPush = {
            pushStarted.complete(Unit)
            release.await()
        }
        val syncing = async { engine.sync() }
        pushStarted.await()

        clock.advanceBy(100)
        repository.updateTodo(id, "edited during push", "")
        release.complete(Unit)

        assertEquals(SyncResult.Success, syncing.await())
        assertEquals("edited during push", row(id)?.title)
        assertEquals(SyncStatus.PENDING, row(id)?.syncStatus, "the newer edit has not reached the server yet")
        assertEquals("original", api.stored.getValue(id).title)

        api.onPush = {}
        assertEquals(SyncResult.Success, engine.sync())
        assertEquals(SyncStatus.SYNCED, row(id)?.syncStatus)
        assertEquals("edited during push", api.stored.getValue(id).title)
    }

    @Test
    fun pushResponseWithANewerServerVersionOverwritesTheLocalRow() = runTest {
        val id = createTodo("mine")
        val mine = store.pendingTodos().single()
        api.seed(mine.copy(title = "edited on another device", updatedAt = mine.updatedAt + 500))

        engine.sync()

        assertEquals("edited on another device", row(id)?.title)
        assertEquals(SyncStatus.SYNCED, row(id)?.syncStatus)
    }

    @Test
    fun pushResponseTieAdoptsTheServersVersion() = runTest {
        val id = createTodo("mine")
        val mine = store.pendingTodos().single()
        // Another device wrote in the same millisecond and the server kept its version.
        api.seed(mine.copy(title = "the server's version"))

        val result = engine.sync()

        assertEquals(SyncResult.Success, result)
        assertEquals("the server's version", row(id)?.title)
        assertEquals(SyncStatus.SYNCED, row(id)?.syncStatus)
        // Nothing left to upload: client and server agree.
        assertEquals(emptySet(), pendingIds())
        assertEquals("the server's version", api.stored.getValue(id).title)
    }

    // ---- pull ---------------------------------------------------------------------------------

    @Test
    fun pullInsertsATodoFromAnotherDeviceAsSynced() = runTest {
        api.seed(remoteVersion("remote-1", "from elsewhere", updatedAt = 500))

        engine.sync()

        assertEquals(SyncStatus.SYNCED, row("remote-1")?.syncStatus)
        assertEquals(listOf("from elsewhere"), repository.observeTodos().first().map { it.title })
    }

    @Test
    fun pullOverwritesALocalRowWithANewerRemoteVersion() = runTest {
        val id = createTodo("will be overwritten")
        engine.sync()
        api.seed(remoteVersion(id, "newer remote", updatedAt = row(id)!!.updatedAt + 100))

        engine.sync()

        assertEquals("newer remote", row(id)?.title)
        assertEquals(SyncStatus.SYNCED, row(id)?.syncStatus)
        // An older or equal remote version is ignored: that rule is tested on SyncStore, because the
        // fake server (like the real one) never hands out a version older than what it stored.
    }

    @Test
    fun pulledTombstoneRemovesTheTodoFromTheList() = runTest {
        val id = createTodo("deleted elsewhere")
        engine.sync()
        api.seed(remoteVersion(id, "deleted elsewhere", updatedAt = row(id)!!.updatedAt + 100, deletedAt = row(id)!!.updatedAt + 100))

        engine.sync()

        assertEquals(emptyList(), repository.observeTodos().first())
        assertTrue(row(id)?.deletedAt != null)
    }

    @Test
    fun newerRemoteEditResurrectsALocalTombstone() = runTest {
        val id = createTodo("deleted here")
        engine.sync()
        clock.advanceBy(10)
        repository.deleteTodo(id)
        engine.sync()
        val tombstoneUpdatedAt = row(id)!!.updatedAt
        api.seed(remoteVersion(id, "edited elsewhere after the delete", updatedAt = tombstoneUpdatedAt + 100))

        engine.sync()

        assertEquals(listOf("edited elsewhere after the delete"), repository.observeTodos().first().map { it.title })
    }

    @Test
    fun pullNeverOverwritesALocalEditThatIsNewerThanTheRemoteVersion() = runTest {
        val id = createTodo("original")
        engine.sync()
        val synced = row(id)!!
        api.seed(remoteVersion(id, "remote edit", updatedAt = synced.updatedAt + 50))
        val pullStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.onPull = {
            pullStarted.complete(Unit)
            release.await()
        }
        val syncing = async { engine.sync() }
        pullStarted.await()

        // The user edits while the pull is in flight, later than the remote edit.
        clock.advanceBy(1_000)
        repository.updateTodo(id, "local edit", "")
        release.complete(Unit)
        syncing.await()

        assertEquals("local edit", row(id)?.title)
        assertEquals(SyncStatus.PENDING, row(id)?.syncStatus)

        api.onPull = {}
        engine.sync()
        assertEquals("local edit", api.stored.getValue(id).title)
    }

    @Test
    fun firstPullSendsNoServerIdAndLaterPullsSendTheStoredCursor() = runTest {
        api.seed(remoteVersion("x", "x", updatedAt = 1))

        engine.sync()
        engine.sync()

        assertEquals(listOf<Pair<Long, String?>>(0L to null, 1L to "server-1"), api.pulls)
        assertEquals(SyncCursor("server-1", 1), store.readCursor())
    }

    // ---- reset --------------------------------------------------------------------------------

    @Test
    fun resetRebuildsTheRestartedServerFromThisClientWithoutChangingUpdatedAt() = runTest {
        val a = createTodo("a")
        val b = createTodo("b")
        engine.sync()
        val updatedAtBefore = mapOf(a to row(a)!!.updatedAt, b to row(b)!!.updatedAt)
        api.restart("server-2")
        assertEquals(emptyMap(), api.stored)

        val result = engine.sync()

        assertEquals(SyncResult.Success, result)
        assertEquals(setOf(a, b), api.stored.keys, "the new server was rebuilt from this client")
        assertEquals(updatedAtBefore, mapOf(a to row(a)!!.updatedAt, b to row(b)!!.updatedAt))
        assertEquals(emptySet(), pendingIds())
        assertEquals("server-2", store.readCursor().serverId)
    }

    @Test
    fun resetDoesNotUploadAgainWhatCameBackFromTheNewServer() = runTest {
        val shared = createTodo("shared")
        val localOnly = createTodo("local only")
        engine.sync()
        // The server restarted and another client already rebuilt `shared` there, with a newer edit.
        api.restart("server-2")
        api.seed(remoteVersion(shared, "newer from another client", updatedAt = row(shared)!!.updatedAt + 100))
        api.pushes.clear()

        engine.sync()

        assertEquals("newer from another client", row(shared)?.title)
        assertEquals(listOf(localOnly), api.pushes.map { it.id }, "only the row the new server lacked is uploaded")
        assertEquals(emptySet(), pendingIds())
    }

    @Test
    fun resetKeepsLocalTombstonesAndUploadsThem() = runTest {
        val id = createTodo("deleted here")
        engine.sync()
        clock.advanceBy(10)
        repository.deleteTodo(id)
        engine.sync()
        api.restart("server-2")

        engine.sync()

        assertTrue(api.stored.getValue(id).deletedAt != null, "the deletion must reach the new server too")
    }

    // ---- errors -------------------------------------------------------------------------------

    @Test
    fun networkErrorMidPushKeepsPushedRowsSyncedTheRestPendingAndTheCursorUnchanged() = runTest {
        val a = createTodo("a")
        val b = createTodo("b")
        val c = createTodo("c")
        api.onPush = { if (it.id == b) throw IOException("offline") }

        val result = engine.sync()

        assertIs<SyncResult.Failure>(result)
        assertIs<IOException>(result.cause)
        assertEquals(emptyList(), result.rejectedIds)
        assertEquals(SyncStatus.SYNCED, row(a)?.syncStatus)
        assertEquals(setOf(b, c), pendingIds())
        assertEquals(SyncCursor(serverId = null, revision = 0), store.readCursor())
        assertEquals(emptyList(), api.pulls, "a network error stops the sync before the pull")

        api.onPush = {}
        assertEquals(SyncResult.Success, engine.sync())
        assertEquals(emptySet(), pendingIds())
        assertEquals(setOf(a, b, c), api.stored.keys)
        assertEquals("server-1", store.readCursor().serverId)
    }

    @Test
    fun aFailedPullLeavesTheCursorAndLocalRowsAsTheyWere() = runTest {
        val id = createTodo("a")
        api.onPull = { throw IOException("offline") }

        val result = engine.sync()

        assertIs<SyncResult.Failure>(result)
        assertEquals(SyncStatus.SYNCED, row(id)?.syncStatus, "the push before it had worked")
        assertEquals(SyncCursor(serverId = null, revision = 0), store.readCursor())
    }

    @Test
    fun aRowTheServerRejectsIsSkippedTheRestIsSyncedAndTheResultIsAFailure() = runTest {
        val a = createTodo("a")
        val bad = createTodo("bad")
        val c = createTodo("c")
        api.seed(remoteVersion("remote-1", "from elsewhere", updatedAt = 5))
        api.onPush = { if (it.id == bad) throw ApiException(400, "title must not be blank") }

        val result = engine.sync()

        assertIs<SyncResult.Failure>(result)
        assertEquals(listOf(bad), result.rejectedIds)
        assertEquals(400, assertIs<ApiException>(result.cause).status)
        assertEquals(SyncStatus.SYNCED, row(a)?.syncStatus)
        assertEquals(SyncStatus.SYNCED, row(c)?.syncStatus, "the row behind the bad one must not be blocked")
        assertEquals(setOf(bad), pendingIds())
        assertEquals(SyncStatus.SYNCED, row("remote-1")?.syncStatus, "the pull still ran")
        assertEquals("server-1", store.readCursor().serverId)
    }

    @Test
    fun aRejectedRowIsLoggedWithItsIdStatusAndReason() = runTest {
        val bad = createTodo("bad")
        api.onPush = { throw ApiException(400, "title must not be blank") }

        engine.sync()

        val line = logs.single { bad in it }
        assertTrue("400" in line && "title must not be blank" in line, "log line was: $line")
    }

    @Test
    fun aRejectedRowIsRetriedOnTheNextSync() = runTest {
        val bad = createTodo("bad")
        api.onPush = { throw ApiException(400, "no") }
        engine.sync()

        api.onPush = {}
        val result = engine.sync()

        assertEquals(SyncResult.Success, result)
        assertEquals(SyncStatus.SYNCED, row(bad)?.syncStatus)
    }

    @Test
    fun serverErrorsAndRetryLaterStatusesStopTheSyncInsteadOfSkippingTheRow() = runTest {
        for (status in listOf(408, 429, 500, 503)) {
            val a = createTodo("a $status")
            val b = createTodo("b $status")
            api.onPush = { if (it.id == a) throw ApiException(status, "later") }
            val pullsBefore = api.pulls.size

            val result = engine.sync()

            assertIs<SyncResult.Failure>(result)
            assertEquals(emptyList(), result.rejectedIds, "HTTP $status is not a rejection")
            assertTrue(b in pendingIds(), "HTTP $status must stop the sync before the next row")
            assertEquals(pullsBefore, api.pulls.size, "HTTP $status must stop the sync before the pull")

            api.onPush = {}
            engine.sync()
        }
    }

    @Test
    fun anUnexpectedExceptionBecomesAFailureInsteadOfEscaping() = runTest {
        createTodo("a")
        api.onPush = { error("something nobody planned for") }

        val result = engine.sync()

        assertIs<IllegalStateException>(assertIs<SyncResult.Failure>(result).cause)
    }

    @Test
    fun cancellationIsRethrownAndTheEngineIsUsableAfterwards() = runTest {
        val id = createTodo("a")
        val pushStarted = CompletableDeferred<Unit>()
        api.onPush = {
            pushStarted.complete(Unit)
            awaitCancellation()
        }
        val syncing = async { engine.sync() }
        pushStarted.await()

        syncing.cancel()

        assertFailsWith<CancellationException> { syncing.await() }
        assertEquals(SyncState.Idle, engine.state.value, "a cancelled sync is not a failure and must not stay Syncing")
        api.onPush = {}
        assertEquals(SyncResult.Success, engine.sync(), "the lock must have been released")
        assertEquals(SyncStatus.SYNCED, row(id)?.syncStatus)
    }

    // ---- single flight ------------------------------------------------------------------------

    @Test
    fun twoConcurrentSyncsRunOneAfterTheOtherAndDoNotPushTheSameVersionTwice() = runTest {
        val id = createTodo("a")
        val pushStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pushAttempts = 0
        api.onPush = {
            pushAttempts += 1
            pushStarted.complete(Unit)
            release.await()
        }
        val first = async { engine.sync() }
        pushStarted.await()
        val second = async { engine.sync() }
        testScheduler.runCurrent() // lets the second call run as far as it can: it must wait for the lock

        assertEquals(1, pushAttempts, "the second sync must not start while the first one is running")
        assertEquals(emptyList(), api.pulls)

        release.complete(Unit)
        val results = awaitAll(first, second)

        assertEquals(listOf(SyncResult.Success, SyncResult.Success), results)
        assertEquals(1, api.pushes.count { it.id == id }, "the same version must not be pushed twice")
        assertEquals(2, api.pulls.size, "the waiting call runs a sync of its own afterwards")
    }

    // ---- state --------------------------------------------------------------------------------

    @Test
    fun stateGoesIdleSyncingIdleOnSuccess() = runTest {
        createTodo("a")
        val states = mutableListOf<SyncState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.state.toList(states) }

        engine.sync()

        assertEquals(listOf(SyncState.Idle, SyncState.Syncing, SyncState.Idle), states)
    }

    @Test
    fun stateIsFailedWithTheTimeFromTheInjectedClockAfterAFailure() = runTest {
        createTodo("a")
        api.onPush = { throw IOException("offline") }
        clock.advanceBy(5_000)

        engine.sync()

        assertEquals(SyncState.Failed(Instant.fromEpochMilliseconds(clock.currentMillis)), engine.state.value)
    }

    @Test
    fun stateReturnsToIdleAfterAFailureIsFollowedByASuccess() = runTest {
        createTodo("a")
        api.onPush = { throw IOException("offline") }
        engine.sync()

        api.onPush = {}
        engine.sync()

        assertEquals(SyncState.Idle, engine.state.value)
    }
}
