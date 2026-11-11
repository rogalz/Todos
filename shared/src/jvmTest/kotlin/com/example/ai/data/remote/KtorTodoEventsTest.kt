package com.example.ai.data.remote

import com.example.ai.api.ApiRoutes
import com.example.ai.api.ServerEvent
import com.example.ai.api.TodoDto
import com.example.ai.server.TodoStore
import com.example.ai.server.module
import com.example.ai.testing.RawSseServer
import com.example.ai.testing.sse
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * [KtorTodoEvents] in two settings, both in real time:
 *
 *  - against the real server in process (`testApplication`): the events the server formats are the
 *    events the client reads, with the production client configuration;
 *  - against [RawSseServer] with the **real OkHttp engine**: everything that needs a real socket
 *    (the socket timeout, closing the connection) and every misbehaving stream.
 */
class KtorTodoEventsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = mutableListOf<HttpClient>()
    private val raws = mutableListOf<RawSseServer>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        clients.forEach { it.close() }
        raws.forEach { it.close() }
    }

    private fun todo(id: String = ID_A, updatedAt: Long = 100) = TodoDto(
        id = id, title = "title", description = "", isDone = false,
        createdAt = 1, updatedAt = updatedAt, deletedAt = null,
    )

    /** One running collection of [TodoEvents.events]: what arrived, and how it ended. */
    private class Collected(val events: Channel<ServerEvent>, val job: Job, private val failure: AtomicReference<Throwable?>) {
        suspend fun next(): ServerEvent = withTimeout(5_000) { events.receive() }
        fun failure(): Throwable? = failure.get()
    }

    private fun collect(events: TodoEvents): Collected {
        val channel = Channel<ServerEvent>(Channel.UNLIMITED)
        val failure = AtomicReference<Throwable?>()
        val job = scope.launch {
            try {
                events.events().collect { channel.send(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                channel.close()
            }
        }
        return Collected(channel, job, failure)
    }

    private fun realClient(requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS): HttpClient =
        HttpClient(OkHttp) { configureTodoClient(requestTimeoutMillis = requestTimeoutMillis) }.also { clients += it }

    private fun raw(status: String = "200 OK", serve: (RawSseServer.Connection) -> Unit): RawSseServer =
        RawSseServer(status, serve).also { raws += it }

    private val hello = sse(ApiRoutes.EVENT_HELLO, """{"serverId":"srv","revision":3}""")
    private val helloEvent = ServerEvent.Hello("srv", 3)

    // ---- against the real server --------------------------------------------------------------

    @Test
    fun aConnectionToTheRealServerStartsWithHelloAndThenReportsEveryAppliedWrite() = testApplication {
        application { module(TodoStore(serverId = "real-server"), heartbeatPeriod = 50.milliseconds) }
        val client = createClient { configureTodoClient() }
        val api = KtorTodoApi(client, baseUrl = "http://localhost")
        val collected = collect(KtorTodoEvents(client, baseUrl = "http://localhost"))

        assertEquals(ServerEvent.Hello("real-server", revision = 0), collected.next())

        api.push(todo(ID_A))
        assertEquals(ServerEvent.Changed(1), collected.next())
        api.push(todo(ID_B))
        assertEquals(ServerEvent.Changed(2), collected.next())
        api.push(todo(ID_B)) // a retry changes nothing, so the next event is the one for the next write
        api.push(todo(ID_A, updatedAt = 200))
        assertEquals(ServerEvent.Changed(3), collected.next())
        collected.job.cancelAndJoin()
    }

    @Test
    fun aStreamThatIsIdleForLongerThanTheRequestTimeoutStaysOpenOnHeartbeats() = testApplication {
        application { module(TodoStore(serverId = "srv"), heartbeatPeriod = 30.milliseconds) }
        // The request timeout is 150 ms; the stream is idle for several times that.
        val client = createClient { configureTodoClient(requestTimeoutMillis = 150) }
        val api = KtorTodoApi(client, baseUrl = "http://localhost")
        val collected = collect(KtorTodoEvents(client, baseUrl = "http://localhost"))
        assertEquals(ServerEvent.Hello("srv", 0), collected.next())

        val started = System.nanoTime()
        delay(700)
        val waitedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(waitedMillis >= 600, "this test only means something if the wait really took 4+ request timeouts, took $waitedMillis ms")
        assertTrue(collected.job.isActive, "the stream was cut: ${collected.failure()}")
        api.push(todo())
        assertEquals(ServerEvent.Changed(1), collected.next(), "and it still delivers")
        collected.job.cancelAndJoin()
    }

    // ---- real engine, scripted streams ----------------------------------------------------------

    @Test
    fun aFlowOnlyConnectsWhenCollectedAndEveryCollectionIsANewConnection() = runBlocking {
        val server = raw { c -> c.send(hello) } // sends hello and ends the stream
        val events = KtorTodoEvents(realClient(), server.baseUrl)

        delay(200)
        assertEquals(0, server.connectionCount, "creating the flow must not connect")

        assertEquals(listOf(helloEvent), withTimeout(5_000) { events.events().toList() })
        assertEquals(listOf(helloEvent), withTimeout(5_000) { events.events().toList() }, "each collection starts with its own hello")
        assertEquals(2, server.connectionCount)
    }

    @Test
    fun cancellingTheCollectorClosesTheConnection() = runBlocking {
        val closed = CountDownLatch(1)
        val server = raw { c ->
            c.send(hello)
            c.awaitClientClose()
            closed.countDown()
        }
        val collected = collect(KtorTodoEvents(realClient(), server.baseUrl))
        assertEquals(helloEvent, collected.next())

        collected.job.cancelAndJoin()

        assertTrue(closed.await(5, TimeUnit.SECONDS), "the server never saw the client leave")
    }

    @Test
    fun aServerThatEndsTheStreamEndsTheFlowNormally() = runBlocking {
        val server = raw { c ->
            c.send(hello)
            c.send(sse(ApiRoutes.EVENT_CHANGED, "4"))
        }

        val received = withTimeout(5_000) { KtorTodoEvents(realClient(), server.baseUrl).events().toList() }

        assertEquals(listOf(helloEvent, ServerEvent.Changed(4)), received, "completing is not an error: the caller reconnects")
    }

    @Test
    fun unknownEventsAndUnreadableDataAreSkippedAndTheStreamGoesOn() = runBlocking {
        val server = raw { c ->
            c.send(sse(event = "surprise", data = "from a newer server"))
            c.send(sse(ApiRoutes.EVENT_HELLO, "this is not json"))
            c.send(sse(ApiRoutes.EVENT_CHANGED, "not a number"))
            c.send(sse(data = "a message without an event name"))
            c.send(hello)
            c.send(sse(ApiRoutes.EVENT_CHANGED, "5"))
        }
        val logged = mutableListOf<String>()

        val received = withTimeout(5_000) { KtorTodoEvents(realClient(), server.baseUrl, log = { logged += it }).events().toList() }

        assertEquals(listOf(helloEvent, ServerEvent.Changed(5)), received)
        assertEquals(2, logged.size, "only the two KNOWN events with bad data are worth a log line: $logged")
        assertTrue(logged.none { "surprise" in it }, "an unknown event is a newer server's business and stays quiet")
    }

    @Test
    fun aHelloWithExtraFieldsIsStillUnderstood() = runBlocking {
        // Tolerant reader: a newer server may add fields to the payload.
        val server = raw { c -> c.send(sse(ApiRoutes.EVENT_HELLO, """{"serverId":"srv","revision":3,"protocol":2}""")) }

        val received = withTimeout(5_000) { KtorTodoEvents(realClient(), server.baseUrl).events().toList() }

        assertEquals(listOf(helloEvent), received)
    }

    @Test
    fun aNon2xxAnswerFailsTheFlow() = runBlocking {
        val server = raw(status = "503 Service Unavailable") { }

        assertFailsWith<SSEClientException> {
            withTimeout(5_000) { KtorTodoEvents(realClient(), server.baseUrl).events().toList() }
        }
        Unit
    }

    @Test
    fun nothingListeningFailsTheFlow() = runBlocking {
        val server = raw { }
        val baseUrl = server.baseUrl
        server.close()

        assertFailsWith<Exception> {
            withTimeout(5_000) { KtorTodoEvents(realClient(), baseUrl).events().toList() }
        }
        Unit
    }

    // ---- the timeouts, with a real socket -------------------------------------------------------

    @Test
    fun aStreamThatGoesSilentEndsOnceTheSocketTimeoutPasses() = runBlocking {
        // Without the explicit socket timeout OkHttp would wait for its own default (10 s) and this
        // test would run into its 5 s limit: that is what the timeout on the request is for.
        //
        // How the flow ends is deliberately not asserted. Ktor's SSE session swallows a
        // CancellationException, so depending on which of two internal paths wins the race the timeout
        // reaches us as `SSEClientException: Read timed out` or as a normal end. LiveUpdates reconnects
        // after either, so the guarantee that matters is that the flow ENDS, and soon.
        val server = raw { c ->
            c.send(hello)
            c.awaitClientClose()
        }
        val collected = collect(KtorTodoEvents(realClient(), server.baseUrl, socketTimeoutMillis = 300))
        assertEquals(helloEvent, collected.next())

        val started = System.nanoTime()
        withTimeout(5_000) { collected.job.join() }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(elapsedMillis in 200..3_000, "the timeout should fire after ~300 ms, took $elapsedMillis ms")
    }

    @Test
    fun heartbeatsKeepAStreamAliveBeyondBothTheSocketAndTheRequestTimeout() = runBlocking {
        // 1.2 s in total, longer than the request timeout (150 ms) and the socket timeout (300 ms),
        // but never silent for longer than 50 ms.
        val server = raw { c ->
            c.send(hello)
            repeat(24) {
                c.sleep(50)
                c.send(sse(comment = "heartbeat"))
            }
            c.send(sse(ApiRoutes.EVENT_CHANGED, "9"))
        }
        val events = KtorTodoEvents(realClient(requestTimeoutMillis = 150), server.baseUrl, socketTimeoutMillis = 300)

        val received = withTimeout(10_000) { events.events().toList() }

        assertEquals(listOf(helloEvent, ServerEvent.Changed(9)), received)
    }

    // ---- the parser, without any network ---------------------------------------------------------

    @Test
    fun parserReadsHelloAndChangedAndIgnoresEverythingElse() {
        assertEquals(helloEvent, parseServerEvent("hello", """{"serverId":"srv","revision":3}"""))
        assertEquals(helloEvent, parseServerEvent("hello", """{"revision":3,"extra":true,"serverId":"srv"}"""))
        assertEquals(ServerEvent.Changed(42), parseServerEvent("changed", "42"))
        assertEquals(ServerEvent.Changed(42), parseServerEvent("changed", " 42\n"))

        assertNull(parseServerEvent("hello", "not json"))
        assertNull(parseServerEvent("hello", """{"serverId":"srv"}"""), "a missing field is not a hello")
        assertNull(parseServerEvent("hello", null))
        assertNull(parseServerEvent("changed", "forty-two"))
        assertNull(parseServerEvent("changed", ""))
        assertNull(parseServerEvent("changed", null))
        assertNull(parseServerEvent("deleted", "42"), "an event name this client does not know")
        assertNull(parseServerEvent(null, "42"), "a default (unnamed) message")
    }

    private companion object {
        const val ID_A = "11111111-1111-4111-8111-111111111111"
        const val ID_B = "22222222-2222-4222-8222-222222222222"
    }
}
