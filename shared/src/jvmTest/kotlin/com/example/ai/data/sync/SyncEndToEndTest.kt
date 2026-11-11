package com.example.ai.data.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.ai.api.ChangesResponse
import com.example.ai.api.TodoDto
import com.example.ai.data.RoomTodoRepository
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.local.SyncStatus
import com.example.ai.data.local.TodoEntity
import com.example.ai.data.remote.ApiException
import com.example.ai.data.remote.KtorTodoApi
import com.example.ai.data.remote.TodoApi
import com.example.ai.data.remote.configureTodoClient
import com.example.ai.server.TodoStore
import com.example.ai.server.module
import com.example.ai.testing.FakeClock
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.first
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The whole sync, with nothing faked except the network cable: the real server (in process,
 * `testApplication`), the real HTTP client configuration, real Room databases, real engines.
 * Each [Client] is a "device" with its own database and engine; they share one clock so that
 * "who edited later" is under the test's control.
 */
class SyncEndToEndTest {

    /** The cable between one client and the server: can be cut, and can be plugged into another server. */
    private class Link : TodoApi {
        var server: TodoApi? = null
        var online = true

        private fun reachable(): TodoApi {
            if (!online) throw IOException("offline")
            return checkNotNull(server) { "no server is running" }
        }

        override suspend fun push(todo: TodoDto) = reachable().push(todo)
        override suspend fun pull(since: Long, serverId: String?): ChangesResponse = reachable().pull(since, serverId)
    }

    private class Client(val link: Link, val clock: FakeClock) {
        val database: AppDatabase = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
        val repository = RoomTodoRepository(database.todoDao(), clock)
        val engine = SyncEngine(link, SyncStore(database, database.syncDao()), clock, log = {})

        suspend fun create(title: String): String {
            clock.advanceBy(10)
            return repository.createTodo(title, description = "")
        }

        suspend fun edit(id: String, title: String) {
            clock.advanceBy(10)
            repository.updateTodo(id, title, description = "")
        }

        suspend fun delete(id: String) {
            clock.advanceBy(10)
            repository.deleteTodo(id)
        }

        /** What the user sees. */
        suspend fun titles(): List<String> = repository.observeTodos().first().map { it.title }

        suspend fun row(id: String): TodoEntity? = database.syncDao().getAnyById(id)

        suspend fun sync(): SyncResult = engine.sync()
    }

    private val clock = FakeClock(startMillis = 1_000_000)
    private val clients = mutableListOf<Client>()

    private fun newClient(link: Link = Link()): Client = Client(link, clock).also { clients += it }

    @AfterTest
    fun tearDown() {
        clients.forEach { it.database.close() }
    }

    /** Runs [block] with a server holding [store] and plugs every given link into it. */
    private fun runWithServer(store: TodoStore = TodoStore(), vararg links: Link, block: suspend () -> Unit) =
        testApplication {
            application { module(store) }
            val api = KtorTodoApi(createClient { configureTodoClient() }, baseUrl = "http://localhost")
            links.forEach { it.server = api }
            block()
        }

    // ---- the four scenarios from the spec -----------------------------------------------------

    @Test
    fun aCreatesAnASyncsBSyncsAndBHasIt() {
        val a = newClient()
        val b = newClient()
        runWithServer(links = arrayOf(a.link, b.link)) {
            val id = a.create("Buy milk")

            assertEquals(SyncResult.Success, a.sync())
            assertEquals(SyncStatus.SYNCED, a.row(id)?.syncStatus)
            assertEquals(emptyList(), b.titles(), "B knows nothing before it syncs")

            assertEquals(SyncResult.Success, b.sync())
            assertEquals(listOf("Buy milk"), b.titles())
            assertEquals(SyncStatus.SYNCED, b.row(id)?.syncStatus)
        }
    }

    @Test
    fun editsOnBothClientsOfflineEndWithTheNewerEditEverywhereWhoeverSyncsFirst() {
        for (aSyncsFirst in listOf(true, false)) {
            val a = newClient()
            val b = newClient()
            runWithServer(links = arrayOf(a.link, b.link)) {
                val id = a.create("Original")
                a.sync()
                b.sync()
                a.link.online = false
                b.link.online = false

                a.edit(id, "A edit (older)")
                b.edit(id, "B edit (newer)")
                a.link.online = true
                b.link.online = true

                val (first, second) = if (aSyncsFirst) a to b else b to a
                first.sync()
                second.sync()
                first.sync() // the first one now learns what the second one pushed

                assertEquals(listOf("B edit (newer)"), a.titles(), "aSyncsFirst=$aSyncsFirst")
                assertEquals(listOf("B edit (newer)"), b.titles(), "aSyncsFirst=$aSyncsFirst")
                assertEquals(SyncStatus.SYNCED, a.row(id)?.syncStatus)
                assertEquals(SyncStatus.SYNCED, b.row(id)?.syncStatus)
            }
        }
    }

    @Test
    fun aDeleteMadeOfflineReachesBAfterAPushThatFailedFirst() {
        val a = newClient()
        val b = newClient()
        runWithServer(links = arrayOf(a.link, b.link)) {
            val id = a.create("Doomed")
            a.sync()
            b.sync()
            assertEquals(listOf("Doomed"), b.titles())

            a.link.online = false
            a.delete(id)
            val failed = a.sync()
            assertIs<SyncResult.Failure>(failed)
            assertEquals(SyncStatus.PENDING, a.row(id)?.syncStatus, "the tombstone waits for the network")
            b.sync()
            assertEquals(listOf("Doomed"), b.titles(), "the server has not heard about the delete yet")

            a.link.online = true
            assertEquals(SyncResult.Success, a.sync())
            assertEquals(SyncResult.Success, b.sync())

            assertEquals(emptyList(), b.titles())
            assertTrue(b.row(id)?.deletedAt != null, "B holds the tombstone, not nothing")
            assertEquals(SyncStatus.SYNCED, b.row(id)?.syncStatus)
        }
    }

    @Test
    fun aServerRestartIsRebuiltFromTheClientsAndANewClientSeesEverything() {
        val a = newClient()
        val b = newClient()
        val c = newClient()
        lateinit var keptId: String
        lateinit var deletedId: String

        runWithServer(TodoStore(serverId = "server-1"), a.link, b.link) {
            keptId = a.create("Kept")
            deletedId = a.create("Deleted before the restart")
            a.create("Also kept")
            a.sync()
            a.delete(deletedId)
            a.sync()
            b.sync()
            assertEquals(2, b.titles().size)
        }

        // The old server is gone with all its data; a fresh one starts with a new id.
        runWithServer(TodoStore(serverId = "server-2"), a.link, b.link, c.link) {
            assertEquals(SyncResult.Success, a.sync(), "A notices the reset and re-uploads everything")
            assertEquals(SyncResult.Success, c.sync())

            assertEquals(setOf("Kept", "Also kept"), c.titles().toSet(), "C, which never saw the old server, gets all of it")
            assertTrue(c.row(deletedId)?.deletedAt != null, "the tombstone was re-uploaded too, so C cannot resurrect it")

            // B restarted its view too: nothing is duplicated or lost, and everyone agrees.
            assertEquals(SyncResult.Success, b.sync())
            assertEquals(setOf("Kept", "Also kept"), b.titles().toSet())
            assertEquals(SyncResult.Success, a.sync())
            assertEquals(c.titles().toSet(), a.titles().toSet())
            assertEquals(SyncStatus.SYNCED, c.row(keptId)?.syncStatus)
        }
    }

    // ---- decisions made for this stage, with the real server ----------------------------------

    @Test
    fun aTieOnUpdatedAtConvergesBecauseTheServerArbitratesThePushResponse() {
        val a = newClient()
        val b = newClient()
        runWithServer(links = arrayOf(a.link, b.link)) {
            val id = a.create("Original")
            a.sync()
            b.sync()

            // Both edit within the same millisecond: the same updatedAt, different content.
            clock.advanceBy(10)
            a.repository.updateTodo(id, "A wins the race to the server", "")
            b.repository.updateTodo(id, "B lost the race", "")
            assertEquals(a.row(id)?.updatedAt, b.row(id)?.updatedAt, "the test needs a real tie")

            a.sync() // the server stores A's version
            b.sync() // the server keeps A's on the tie and answers with it; B must adopt it
            a.sync()

            assertEquals(listOf("A wins the race to the server"), a.titles())
            assertEquals(listOf("A wins the race to the server"), b.titles(), "without the server as arbiter B would differ forever")
            assertEquals(SyncStatus.SYNCED, b.row(id)?.syncStatus)
        }
    }

    @Test
    fun aRowTheRealServerRejectsDoesNotBlockTheOthers() {
        val a = newClient()
        val b = newClient()
        runWithServer(links = arrayOf(a.link, b.link)) {
            val first = a.create("Fine")
            // A legacy row with a blank title: the UI cannot create one, the server refuses it (HTTP 400).
            val blank = a.create("placeholder")
            a.database.todoDao().updateContent(blank, title = "", description = "", now = clock.currentMillis)
            val last = a.create("Also fine")

            val result = a.sync()

            assertIs<SyncResult.Failure>(result)
            assertEquals(listOf(blank), result.rejectedIds)
            assertEquals(400, assertIs<ApiException>(result.cause).status)
            assertEquals(SyncStatus.PENDING, a.row(blank)?.syncStatus)
            assertEquals(SyncStatus.SYNCED, a.row(first)?.syncStatus)
            assertEquals(SyncStatus.SYNCED, a.row(last)?.syncStatus, "the row behind the rejected one still went through")

            b.sync()
            assertEquals(setOf("Fine", "Also fine"), b.titles().toSet())
        }
    }

    @Test
    fun deletingEverythingAndSyncingAgainIsStable() {
        // Nothing to push and nothing to pull is a success, not an error, and changes nothing.
        val a = newClient()
        runWithServer(links = arrayOf(a.link)) {
            assertEquals(SyncResult.Success, a.sync())
            assertEquals(SyncResult.Success, a.sync())
            assertEquals(emptyList(), a.titles())
        }
    }
}
