package com.example.ai.data.remote

import com.example.ai.api.TodoDto
import com.example.ai.server.TodoStore
import com.example.ai.server.module
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KtorTodoApi` against the real server, in process (`testApplication`, no network), with the
 * production client configuration. A few tests replace the server with a stub that misbehaves.
 */
class KtorTodoApiTest {

    private val id = "2d9a3a0e-1111-4222-8333-444455556666"

    private fun todo(
        id: String = this.id,
        title: String = "Buy milk",
        updatedAt: Long = 100,
        deletedAt: Long? = null,
    ) = TodoDto(id, title, description = "2 liters", isDone = false, createdAt = 50, updatedAt = updatedAt, deletedAt = deletedAt)

    private fun ApplicationTestBuilder.api(
        connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
        requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
        baseUrl: String = "http://localhost",
    ) = KtorTodoApi(
        client = createClient { configureTodoClient(connectTimeoutMillis, requestTimeoutMillis) },
        baseUrl = baseUrl,
    )

    private fun runWithRealServer(store: TodoStore = TodoStore(), block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { module(store) }
            block()
        }

    private fun runWithStub(stub: Application.() -> Unit, block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application(stub)
            block()
        }

    // ---- against the real server --------------------------------------------------------------

    @Test
    fun pushedTodoComesBackAsStoredAndShowsUpInThePull() = runWithRealServer {
        val api = api()

        val stored = api.push(todo())
        val changes = api.pull(since = 0, serverId = null)

        assertEquals(todo(), stored)
        assertEquals(listOf(todo()), changes.items)
        assertEquals(1L, changes.revision)
        assertFalse(changes.reset)
        assertTrue(changes.serverId.isNotBlank())
    }

    @Test
    fun pushOfAnOlderVersionReturnsTheNewerStoredOne() = runWithRealServer {
        val api = api()
        api.push(todo(title = "newer", updatedAt = 200))

        val answer = api.push(todo(title = "stale", updatedAt = 100))

        assertEquals("newer", answer.title)
        assertEquals(200L, answer.updatedAt)
    }

    @Test
    fun pushedTombstoneIsReturnedAndPulledWithItsDeletedAt() = runWithRealServer {
        val api = api()

        val answer = api.push(todo(updatedAt = 100, deletedAt = 100))

        assertEquals(100L, answer.deletedAt)
        assertEquals(100L, api.pull(0, null).items.single().deletedAt)
    }

    @Test
    fun pullSendsTheCursorSoOnlyLaterChangesComeBack() = runWithRealServer {
        val api = api()
        val other = "9b8a7c6d-1111-4222-8333-444455556666"
        api.push(todo())
        val first = api.pull(since = 0, serverId = null)
        api.push(todo(id = other, title = "second"))

        val delta = api.pull(since = first.revision, serverId = first.serverId)

        assertEquals(listOf("second"), delta.items.map { it.title })
        assertFalse(delta.reset)
    }

    @Test
    fun pullWithAnotherServerIdIsAResetWithTheFullSnapshot() = runWithRealServer {
        val api = api()
        api.push(todo())

        val changes = api.pull(since = 99, serverId = "a-server-that-no-longer-exists")

        assertTrue(changes.reset)
        assertEquals(1, changes.items.size)
    }

    @Test
    fun trailingSlashInTheBaseUrlIsHarmless() = runWithRealServer {
        val api = api(baseUrl = "http://localhost/")

        assertEquals(todo(), api.push(todo()))
    }

    // ---- errors -------------------------------------------------------------------------------

    @Test
    fun aValidationErrorBecomesAnApiExceptionThatIsARejection() = runWithRealServer {
        val api = api()

        val error = assertFailsWith<ApiException> { api.push(todo(title = "   ")) }

        assertEquals(400, error.status)
        assertTrue(error.isRejection)
        assertTrue("title" in error.body, "the server explains what is wrong: ${error.body}")
    }

    @Test
    fun aServerErrorBecomesAnApiExceptionThatIsNotARejection() = runWithStub({
        routing { put("/todos/{id}") { call.respondText("boom", status = HttpStatusCode.InternalServerError) } }
    }) {
        val error = assertFailsWith<ApiException> { api().push(todo()) }

        assertEquals(500, error.status)
        assertFalse(error.isRejection)
    }

    @Test
    fun aFailedPullBecomesAnApiException() = runWithStub({
        routing { get("/todos") { call.respondText("unavailable", status = HttpStatusCode.ServiceUnavailable) } }
    }) {
        val error = assertFailsWith<ApiException> { api().pull(0, null) }

        assertEquals(503, error.status)
    }

    @Test
    fun onlyPermanent4xxAreRejections() {
        assertTrue(ApiException(400, "").isRejection)
        assertTrue(ApiException(404, "").isRejection)
        assertTrue(ApiException(422, "").isRejection)
        // "Try again later", not "this request is wrong".
        assertFalse(ApiException(408, "").isRejection)
        assertFalse(ApiException(429, "").isRejection)
        assertFalse(ApiException(500, "").isRejection)
        assertFalse(ApiException(503, "").isRejection)
        assertFalse(ApiException(302, "").isRejection)
    }

    @Test
    fun aServerThatNeverAnswersTimesOutInsteadOfHanging() = runWithStub({
        routing { put("/todos/{id}") { delay(5_000); call.respondText("too late") } }
    }) {
        // A short timeout only for the test; production uses the defaults.
        val api = api(requestTimeoutMillis = 200)

        assertFailsWith<HttpRequestTimeoutException> { api.push(todo()) }
    }

    @Test
    fun theProductionTimeoutsAreFiniteAndSensible() {
        assertEquals(5_000L, DEFAULT_CONNECT_TIMEOUT_MILLIS)
        assertEquals(15_000L, DEFAULT_REQUEST_TIMEOUT_MILLIS)
    }

    // ---- tolerant JSON ------------------------------------------------------------------------

    @Test
    fun unknownFieldsFromANewerServerAreIgnored() = runWithStub({
        routing {
            get("/todos") {
                call.respondText(
                    """{"items":[{"id":"$id","title":"t","description":"","isDone":false,"createdAt":1,"updatedAt":2,"deletedAt":null,"color":"red"}],""" +
                        """"revision":3,"serverId":"s","reset":false,"nextPage":"later"}""",
                    ContentType.Application.Json,
                )
            }
        }
    }) {
        val changes = api().pull(0, null)

        assertEquals("t", changes.items.single().title)
        assertEquals(3L, changes.revision)
        assertNull(changes.items.single().deletedAt)
        assertNotNull(changes.serverId)
    }
}
