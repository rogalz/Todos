package com.example.ai.server

import com.example.ai.api.ApiRoutes
import com.example.ai.api.ServerEvent
import com.example.ai.api.TodoDto
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * `GET /events` through `testApplication`. Real time: the server and the client run on real
 * dispatchers, so every wait has a timeout and a test that gets no event fails instead of hanging.
 */
class EventRoutesTest {

    private val json = Json

    /** One open `GET /events` connection, as a client sees it. Comments are not events unless asked for. */
    private class Stream(private val received: Channel<ServerSentEvent>, private val job: Job) {
        suspend fun next(): ServerSentEvent = withTimeout(5_000) { received.receive() }

        /** For "nothing arrives": a bounded wait, since an absent event has no signal to wait for. */
        suspend fun nextOrNullWithin(millis: Long): ServerSentEvent? =
            withTimeoutOrNull(millis) { received.receive() }

        suspend fun close() = job.cancelAndJoin()
    }

    private class Harness(val client: HttpClient, val store: TodoStore, private val scope: CoroutineScope) {
        fun open(): Stream {
            val received = Channel<ServerSentEvent>(Channel.UNLIMITED)
            val job = scope.launch { client.sse(urlString = ApiRoutes.EVENTS) { incoming.collect { received.send(it) } } }
            return Stream(received, job)
        }

        suspend fun put(dto: TodoDto) {
            val response = client.put(ApiRoutes.todoPath(dto.id)) {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(dto))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    private fun eventsTest(
        store: TodoStore = TodoStore(serverId = SERVER_ID),
        heartbeatMillis: Long = 60_000, // long: tests that are not about heartbeats see none
        showComments: Boolean = false,
        block: suspend Harness.() -> Unit,
    ) = testApplication {
        application { module(store, heartbeatPeriod = heartbeatMillis.milliseconds) }
        val client = createClient { install(SSE) { if (showComments) showCommentEvents() } }
        // Its own scope: streams are long-lived, so they must be cancelled at the end or the test never returns.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            Harness(client, store, scope).block()
        } finally {
            scope.cancel()
        }
    }

    private fun todo(id: String = ID_A, updatedAt: Long = 100, title: String = "title") = TodoDto(
        id = id, title = title, description = "", isDone = false,
        createdAt = 1, updatedAt = updatedAt, deletedAt = null,
    )

    private fun ServerSentEvent.asHello(): ServerEvent.Hello {
        assertEquals(ApiRoutes.EVENT_HELLO, event)
        return json.decodeFromString(checkNotNull(data) { "hello without data" })
    }

    private fun ServerSentEvent.asChanged(): ServerEvent.Changed {
        assertEquals(ApiRoutes.EVENT_CHANGED, event)
        return ServerEvent.Changed(checkNotNull(data).toLong())
    }

    // ---- connect ------------------------------------------------------------------------------

    @Test
    fun connectingSendsHelloWithTheServerIdAndTheCurrentRevision() = eventsTest {
        store.upsert(todo(id = ID_A))
        store.upsert(todo(id = ID_B))

        val stream = open()

        assertEquals(ServerEvent.Hello(SERVER_ID, revision = 2), stream.next().asHello())
        stream.close()
    }

    @Test
    fun aFreshServerSaysHelloWithRevisionZero() = eventsTest {
        val stream = open()

        assertEquals(ServerEvent.Hello(SERVER_ID, revision = 0), stream.next().asHello())
        stream.close()
    }

    @Test
    fun writesMadeBeforeConnectingAreNotReplayed() = eventsTest {
        store.upsert(todo())
        val stream = open()
        stream.next().asHello()

        assertNull(stream.nextOrNullWithin(300), "an old write is covered by the revision in hello, not by an event")
        stream.close()
    }

    // ---- changed ------------------------------------------------------------------------------

    @Test
    fun anAppliedPutSendsChangedWithTheNewRevision() = eventsTest {
        val stream = open()
        stream.next().asHello()

        put(todo(id = ID_A))
        assertEquals(ServerEvent.Changed(1), stream.next().asChanged())
        put(todo(id = ID_B))
        assertEquals(ServerEvent.Changed(2), stream.next().asChanged())
        put(todo(id = ID_A, updatedAt = 200, title = "edited"))
        assertEquals(ServerEvent.Changed(3), stream.next().asChanged())
        stream.close()
    }

    @Test
    fun aNoOpPutSendsNothing() = eventsTest {
        val stream = open()
        stream.next().asHello()
        put(todo(updatedAt = 100, title = "stored"))
        assertEquals(ServerEvent.Changed(1), stream.next().asChanged())

        put(todo(updatedAt = 100, title = "stored")) // an exact retry
        put(todo(updatedAt = 100, title = "a tie: the stored version stays"))
        put(todo(updatedAt = 50, title = "older"))
        // Events leave in order, so if any of the three had emitted, it would arrive before this one.
        put(todo(id = ID_B))

        assertEquals(ServerEvent.Changed(2), stream.next().asChanged(), "revision 2, so the no-ops did not even bump it")
        stream.close()
    }

    @Test
    fun aWriteRightAfterHelloIsNotMissed() = eventsTest {
        // hello is the signal that the subscription is live: whatever is written after it must arrive.
        val stream = open()
        stream.next().asHello()

        repeat(20) { n -> put(todo(id = uuid(n))) }

        for (revision in 1L..20L) assertEquals(ServerEvent.Changed(revision), stream.next().asChanged())
        stream.close()
    }

    @Test
    fun everyConnectedClientReceivesTheEvent() = eventsTest {
        val first = open()
        val second = open()
        first.next().asHello()
        second.next().asHello()

        put(todo())

        assertEquals(ServerEvent.Changed(1), first.next().asChanged())
        assertEquals(ServerEvent.Changed(1), second.next().asChanged())
        first.close()
        second.close()
    }

    @Test
    fun aClientThatLeavesDoesNotDisturbTheOthers() = eventsTest {
        val leaving = open()
        val staying = open()
        leaving.next().asHello()
        staying.next().asHello()

        leaving.close()
        put(todo())

        assertEquals(ServerEvent.Changed(1), staying.next().asChanged())
        staying.close()
    }

    // ---- heartbeat ----------------------------------------------------------------------------

    @Test
    fun anIdleStreamKeepsReceivingHeartbeatComments() = eventsTest(heartbeatMillis = 40, showComments = true) {
        val stream = open()
        val seen = mutableListOf<ServerSentEvent>()

        // hello plus at least three heartbeats, with nothing written to the store in between
        while (seen.count { it.comments != null } < 3) seen += stream.next()

        assertEquals(ServerEvent.Hello(SERVER_ID, 0), seen.first { it.event == ApiRoutes.EVENT_HELLO }.asHello())
        assertEquals(setOf("heartbeat"), seen.mapNotNull { it.comments }.toSet())
        stream.close()
    }

    @Test
    fun heartbeatsDoNotShowUpAsEventsForAClientThatDoesNotAskForComments() = eventsTest(heartbeatMillis = 20) {
        val stream = open()
        stream.next().asHello()

        delay(200) // ten heartbeats
        put(todo())

        assertEquals(ServerEvent.Changed(1), stream.next().asChanged(), "the first event after hello is the change")
        stream.close()
    }

    /** A valid (version 4) UUID that differs per [n]: the server refuses ids that are not UUIDs. */
    private fun uuid(n: Int) = "${n.toString().padStart(8, '0')}-0000-4000-8000-000000000000"

    private companion object {
        const val SERVER_ID = "server-under-test"
        const val ID_A = "11111111-1111-4111-8111-111111111111"
        const val ID_B = "22222222-2222-4222-8222-222222222222"
    }
}
