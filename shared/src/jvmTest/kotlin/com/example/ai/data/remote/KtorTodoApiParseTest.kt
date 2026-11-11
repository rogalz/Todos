package com.example.ai.data.remote

import com.example.ai.api.ApiRoutes
import com.example.ai.api.ParsedTodoDto
import com.example.ai.server.LlmBadOutputException
import com.example.ai.server.LlmClient
import com.example.ai.server.LlmUnavailableException
import com.example.ai.server.module
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `TodoApi.parse` against the real server (with a fake LLM behind it), with the production client configuration. */
class KtorTodoApiParseTest {

    private fun ApplicationTestBuilder.api(requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS) =
        KtorTodoApi(
            client = createClient { configureTodoClient(requestTimeoutMillis = requestTimeoutMillis) },
            baseUrl = "http://localhost",
        )

    private fun llm(answer: suspend (String) -> ParsedTodoDto) = object : LlmClient {
        override suspend fun parse(text: String): ParsedTodoDto = answer(text)
    }

    private fun runWithRealServer(llm: LlmClient, block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { module(llm = llm) }
            block()
        }

    private fun runWithStub(stub: Application.() -> Unit, block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application(stub)
            block()
        }

    @Test
    fun aSentenceComesBackAsTheDraftTheServerPolished() = runWithRealServer(llm { ParsedTodoDto("  Buy milk  ", "2 l") }) {
        assertEquals(ParsedTodoDto("Buy milk", "2 l"), api().parse("uh, buy milk, two litres"))
    }

    @Test
    fun theSentenceTravelsAsTheRequestBody() {
        val seen = mutableListOf<String>()
        runWithRealServer(llm { seen += it; ParsedTodoDto("t", "") }) {
            api().parse("Zażółć gęślą jaźń 🚀\nline2 \"quoted\"")
        }

        assertEquals(listOf("Zażółć gęślą jaźń 🚀\nline2 \"quoted\""), seen)
    }

    @Test
    fun aBlankSentenceIsARejection() = runWithRealServer(llm { ParsedTodoDto("t", "") }) {
        val failure = assertFailsWith<ApiException> { api().parse("   ") }

        assertEquals(400, failure.status)
        assertTrue(failure.isRejection)
    }

    @Test
    fun anLlmThatIsDownIs503AndNotARejection() = runWithRealServer(llm { throw LlmUnavailableException("down") }) {
        val failure = assertFailsWith<ApiException> { api().parse("buy milk") }

        assertEquals(503, failure.status)
        assertFalse(failure.isRejection)
    }

    @Test
    fun garbageFromTheLlmIs502() = runWithRealServer(llm { throw LlmBadOutputException("garbage") }) {
        assertEquals(502, assertFailsWith<ApiException> { api().parse("buy milk") }.status)
    }

    // The 30 s of parse is a per-call override; the client's default stays what the other calls use.
    @Test
    fun parseIsNotCutShortByTheClientsDefaultTimeoutButPullIs() = runWithStub({
        routing {
            post(ApiRoutes.PARSE) {
                delay(400)
                call.respondText("""{"title":"slow","description":""}""", io.ktor.http.ContentType.Application.Json)
            }
            get(ApiRoutes.TODOS) {
                delay(400)
                call.respondText("{}")
            }
        }
    }) {
        val api = api(requestTimeoutMillis = 100)

        assertEquals(ParsedTodoDto("slow", ""), api.parse("x"))
        assertFailsWith<HttpRequestTimeoutException> { api.pull(since = 0, serverId = null) }
    }

    @Test
    fun theParseTimeoutIsThirtySeconds() {
        assertEquals(30_000L, PARSE_REQUEST_TIMEOUT_MILLIS)
    }
}
