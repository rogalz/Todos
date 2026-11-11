package com.example.ai.di

import com.example.ai.api.ServerEvent
import com.example.ai.api.TodoDto
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.remote.TodoApi
import com.example.ai.data.remote.TodoEvents
import com.example.ai.data.sync.AppForeground
import com.example.ai.data.sync.LiveUpdates
import com.example.ai.data.sync.SyncScheduler
import com.example.ai.domain.TodoRepository
import com.example.ai.testing.FakeAppForeground
import com.example.ai.testing.FakeTodoApi
import com.example.ai.testing.FakeTodoEvents
import com.example.ai.testing.fakePlatformModule
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The live-updates pieces as wired by the real [appModule]: only the server (`TodoApi`) and the event
 * stream (`TodoEvents`) are fakes. The unit tests build `LiveUpdates` by hand, so only a test like
 * this catches a binding that hands it another cursor store or another scheduler than the rest of the
 * graph uses. Real time (the workers run on `Dispatchers.Default`), so waits are bounded polls.
 */
class LiveUpdatesWiringTest {

    private val api = FakeTodoApi(serverId = "server-1")
    private val events = FakeTodoEvents()
    private val foreground = FakeAppForeground(initial = true)
    private val app: KoinApplication = koinApplication {
        modules(
            fakePlatformModule(),
            appModule,
            module {
                single<TodoApi> { api }
                single<TodoEvents> { events }
                single<AppForeground> { foreground }
            },
        )
    }
    private val koin get() = app.koin

    @AfterTest
    fun tearDown() {
        koin.getOrNull<AppDatabase>()?.close()
        koin.getOrNull<HttpClient>()?.close()
        koin.getOrNull<CoroutineScope>(AppScope)?.cancel()
        app.close()
    }

    private suspend fun waitUntil(what: String, condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }.also { assertTrue(condition(), what) }

    @Test
    fun resolvingLiveUpdatesDoesNotOpenAConnection() = runBlocking {
        koin.get<LiveUpdates>()

        // Real time on purpose: a started one connects at once.
        withContext(Dispatchers.Default) { delay(300) }

        assertEquals(0, events.connections.size, "only startKoinApp may start it, not building the graph")
    }

    @Test
    fun startingItConnectsAndAHelloMakesTheGraphsSchedulerSyncAtOnce() = runBlocking {
        koin.get<SyncScheduler>().start()
        waitUntil("the sync on start") { api.pulls.size == 1 }

        koin.get<LiveUpdates>().start()
        waitUntil("a connection") { events.connections.size == 1 }
        events.connections.single().send(ServerEvent.Hello("server-1", revision = 0))

        waitUntil("the sync that the hello asked for") { api.pulls.size == 2 }
    }

    @Test
    fun aChangedEventAboveTheStoredCursorPullsIntoTheRepositoryTheUiObserves() = runBlocking {
        koin.get<SyncScheduler>().start()
        waitUntil("the sync on start") { api.pulls.size == 1 } // stores the cursor (server-1, 0)
        koin.get<LiveUpdates>().start()
        waitUntil("a connection") { events.connections.size == 1 }
        val connection = events.connections.single()
        connection.send(ServerEvent.Hello("server-1", revision = 0))
        waitUntil("the hello sync") { api.pulls.size == 2 }

        // Another device wrote something; the server announces it.
        api.seed(TodoDto("remote-1", "From another device", "", false, createdAt = 1, updatedAt = 2, deletedAt = null))
        connection.send(ServerEvent.Changed(revision = 1))

        waitUntil("the sync that the event asked for") { api.pulls.size == 3 }
        val titles = koin.get<TodoRepository>().observeTodos().first().map { it.title }
        assertEquals(listOf("From another device"), titles, "the change arrived without a local edit and without calling sync()")

        // And now the cursor says revision 1, so the same event again is the echo and changes nothing.
        connection.send(ServerEvent.Changed(revision = 1))
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(3, api.pulls.size, "read from the same store the engine writes to")
    }

    @Test
    fun theForegroundSignalOfTheGraphGatesTheConnection() = runBlocking {
        foreground.set(false)
        koin.get<LiveUpdates>().start()
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(0, events.connections.size)

        foreground.set(true)
        waitUntil("a connection once in the foreground") { events.connections.size == 1 }

        foreground.set(false)
        waitUntil("the connection to be cancelled") { events.activeConnections == 0 }
    }
}
