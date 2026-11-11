package com.example.ai.data.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.ai.api.ServerEvent
import com.example.ai.data.RoomTodoRepository
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.remote.KtorTodoApi
import com.example.ai.data.remote.KtorTodoEvents
import com.example.ai.data.remote.TodoApi
import com.example.ai.data.remote.TodoEvents
import com.example.ai.data.remote.configureTodoClient
import com.example.ai.domain.Todo
import com.example.ai.server.TodoStore
import com.example.ai.server.module
import com.example.ai.testing.FakeAppForeground
import com.example.ai.testing.FakeClock
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Live updates with nothing faked except the network cable: the real server (in process), the real
 * SSE stream and client, real Room databases, real scheduler, engine and [LiveUpdates]. Each [Device]
 * is one "app" with its own database. **No test here ever calls `sync()` itself**: whatever arrives
 * must have come through the stream. Real time, so every wait is a bounded poll.
 */
class LiveUpdatesEndToEndTest {

    /** Counts what came through the stream, so a test can tell a catch-up from a live event. */
    private class RecordingEvents(private val inner: TodoEvents) : TodoEvents {
        val hellos = AtomicInteger()
        val changes = AtomicInteger()

        override fun events(): Flow<ServerEvent> = inner.events().onEach {
            when (it) {
                is ServerEvent.Hello -> hellos.incrementAndGet()
                is ServerEvent.Changed -> changes.incrementAndGet()
            }
        }
    }

    private class Device(
        api: TodoApi,
        events: TodoEvents,
        private val clock: FakeClock,
        scope: CoroutineScope,
        val foreground: FakeAppForeground = FakeAppForeground(initial = true),
    ) {
        val database: AppDatabase = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
        val repository = RoomTodoRepository(database.todoDao(), clock)
        val stream = RecordingEvents(events)

        val syncsStarted = AtomicInteger()
        val syncsFinished = AtomicInteger()

        private val store = SyncStore(database, database.syncDao())
        private val engine = SyncEngine(api, store, clock, log = {})

        // A short debounce so the tests do not wait the production 500 ms for every local edit.
        private val scheduler = SyncScheduler(
            pendingCount = store.observePendingCount(),
            sync = {
                syncsStarted.incrementAndGet()
                engine.sync().also { syncsFinished.incrementAndGet() }
            },
            scope = scope,
            debounceMillis = 50,
        )
        private val liveUpdates = LiveUpdates(
            events = stream,
            foreground = foreground,
            readCursor = store::readCursor,
            requestSync = scheduler::requestSync,
            serverIsReachable = scheduler::serverIsReachable,
            scope = scope,
            log = {},
        )

        fun start() {
            scheduler.start()
            liveUpdates.start()
        }

        suspend fun create(title: String): String {
            clock.advanceBy(10)
            return repository.createTodo(title, description = "")
        }

        suspend fun edit(id: String, title: String) {
            clock.advanceBy(10)
            repository.updateTodo(id, title, description = "")
        }

        suspend fun tick(id: String) {
            clock.advanceBy(10)
            repository.setDone(id, true)
        }

        suspend fun delete(id: String) {
            clock.advanceBy(10)
            repository.deleteTodo(id)
        }

        /** What the user sees. */
        suspend fun todos(): List<Todo> = repository.observeTodos().first()
        suspend fun titles(): List<String> = todos().map { it.title }
    }

    private val clock = FakeClock(startMillis = 1_000_000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val devices = mutableListOf<Device>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        devices.forEach { it.database.close() }
    }

    /** A server in process (and its store, to look at) with two devices on it; the block runs with them. */
    private fun twoDevices(block: suspend (a: Device, b: Device, server: TodoStore) -> Unit) = testApplication {
        val server = TodoStore()
        application { module(server, heartbeatPeriod = 100.milliseconds) }
        val client = createClient { configureTodoClient() }
        val api = KtorTodoApi(client, baseUrl = "http://localhost")
        val events = KtorTodoEvents(client, baseUrl = "http://localhost")
        val a = Device(api, events, clock, scope).also { devices += it }
        val b = Device(api, events, clock, scope).also { devices += it }
        try {
            block(a, b, server)
        } finally {
            scope.cancel() // closes the streams, or the test application would wait for them
        }
    }

    private suspend fun eventually(what: String, timeoutMillis: Long = 5_000, condition: suspend () -> Boolean) {
        try {
            withTimeout(timeoutMillis) { while (!condition()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out after $timeoutMillis ms waiting for: $what")
        }
    }

    /** Waits until the device has connected and has no sync running or being asked for, then returns. */
    private suspend fun Device.settle() {
        eventually("a connection (hello received)") { stream.hellos.get() >= 1 }
        var lastCount = -1
        var stableSince = System.nanoTime()
        eventually("no more syncs") {
            val count = syncsFinished.get()
            if (count != lastCount || syncsStarted.get() != count) {
                lastCount = count
                stableSince = System.nanoTime()
            }
            (System.nanoTime() - stableSince) / 1_000_000 >= 250
        }
    }

    // ---- the scenario from the spec -----------------------------------------------------------

    @Test
    fun aTodoCreatedAndSyncedOnBAppearsInAsDatabaseWithoutAEverCallingSync() = twoDevices { a, b, server ->
        a.start()
        a.settle() // A is connected and idle: whatever happens next can only come through the stream
        val changesBefore = a.stream.changes.get()
        val syncsBefore = a.syncsFinished.get()

        b.start()
        b.create("From B")

        eventually("A to have the todo") { a.titles() == listOf("From B") }
        assertTrue(a.stream.changes.get() > changesBefore, "it arrived through a `changed` event, not through a catch-up")
        assertTrue(a.syncsFinished.get() > syncsBefore, "and the sync that fetched it was started by the stream")
    }

    @Test
    fun createEditTickAndDeleteTravelInBothDirectionsAndThenEverythingGoesQuiet() = twoDevices { a, b, server ->
        a.start()
        b.start()
        a.settle()
        b.settle()

        val id = a.create("Buy milk")
        eventually("B has it") { b.titles() == listOf("Buy milk") }

        b.edit(id, "Buy oat milk")
        eventually("A sees B's edit") { a.titles() == listOf("Buy oat milk") }

        a.tick(id)
        eventually("B sees it ticked") { b.todos().singleOrNull()?.isDone == true }

        b.delete(id)
        eventually("A sees the delete") { a.titles().isEmpty() }

        val other = b.create("Written last")
        eventually("A has it") { a.titles() == listOf("Written last") }
        assertEquals(other, a.todos().single().id)

        // The echo of every push comes back as a `changed`, and a pull never writes a PENDING row, so
        // nothing may keep ping-ponging between the two devices once the last change has landed.
        a.settle()
        b.settle()
        val syncsA = a.syncsFinished.get()
        val syncsB = b.syncsFinished.get()
        delay(600)
        assertEquals(syncsA, a.syncsFinished.get(), "A kept syncing with nothing to do")
        assertEquals(syncsB, b.syncsFinished.get(), "B kept syncing with nothing to do")
    }

    @Test
    fun aDeviceInTheBackgroundMissesTheLiveUpdateAndCatchesUpTheMomentItComesBack() = twoDevices { a, b, server ->
        a.start()
        b.start()
        a.settle()
        b.settle()

        a.foreground.set(false) // the stream is cancelled
        val helloCount = a.stream.hellos.get()
        b.create("Written while A is in the background")
        eventually("B's change to be on the server") {
            server.changes(since = 0, clientServerId = null).items.any { it.title == "Written while A is in the background" }
        }
        delay(500)
        assertEquals(emptyList(), a.titles(), "no stream, no live update")

        a.foreground.set(true) // reconnect: the hello is what catches up

        eventually("A to catch up") { a.titles() == listOf("Written while A is in the background") }
        assertTrue(a.stream.hellos.get() > helloCount, "through the new connection's hello")
    }
}
