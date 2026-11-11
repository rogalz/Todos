package com.example.ai.di

import com.example.ai.api.TodoDto
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.remote.TodoApi
import com.example.ai.data.sync.SyncEngine
import com.example.ai.data.sync.SyncResult
import com.example.ai.data.sync.SyncScheduler
import com.example.ai.data.sync.SyncStore
import com.example.ai.domain.TodoRepository
import com.example.ai.testing.FakeTodoApi
import com.example.ai.testing.fakePlatformModule
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sync pieces as wired by the real [appModule]: the unit tests build them by hand, so only a
 * test like this can catch a binding that hands the engine a different database or store than the
 * repository writes to. Only the server is replaced, by a fake.
 */
class SyncWiringTest {

    private val api = FakeTodoApi()
    private val app: KoinApplication = koinApplication {
        modules(fakePlatformModule(), appModule, module { single<TodoApi> { api } })
    }
    private val koin get() = app.koin

    @AfterTest
    fun tearDown() {
        koin.getOrNull<AppDatabase>()?.close()
        koin.getOrNull<HttpClient>()?.close()
        koin.getOrNull<CoroutineScope>(AppScope)?.cancel()
        app.close()
    }

    @Test
    fun aTodoWrittenThroughTheRepositoryIsUploadedByTheEngineFromTheSameGraph() = runTest {
        val id = koin.get<TodoRepository>().createTodo(title = "From the graph", description = "")
        assertEquals(1, koin.get<SyncStore>().observePendingCount().first())

        val result = koin.get<SyncEngine>().sync()

        assertEquals(SyncResult.Success, result)
        assertEquals("From the graph", api.stored.getValue(id).title)
        assertEquals(0, koin.get<SyncStore>().observePendingCount().first(), "engine and repository share one database")
    }

    @Test
    fun aTodoFromTheServerShowsUpInTheRepositoryTheUiObserves() = runTest {
        api.seed(
            TodoDto("remote-1", "From another device", "", false, createdAt = 1, updatedAt = 2, deletedAt = null),
        )

        koin.get<SyncEngine>().sync()

        assertEquals(listOf("From another device"), koin.get<TodoRepository>().observeTodos().first().map { it.title })
    }

    @Test
    fun resolvingTheSchedulerDoesNotStartSyncing() = runTest {
        koin.get<SyncScheduler>()

        // Real time on purpose: a started scheduler syncs at once, so the fake server would see a pull.
        withContext(Dispatchers.Default) { delay(300) }

        assertTrue(api.pulls.isEmpty(), "only startKoinApp may start the scheduler, not building the graph")
    }

    @Test
    fun startingTheSchedulerSyncsOnStart() = runTest {
        koin.get<SyncScheduler>().start()

        // The worker runs in the app scope on Dispatchers.Default, so wait in real time for the pull.
        withContext(Dispatchers.Default) {
            repeat(50) { if (api.pulls.isEmpty()) delay(20) }
        }

        assertEquals(1, api.pulls.size)
    }
}
