package com.example.ai.server

import com.example.ai.api.ParsedTodoDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [OllamaLlmClient] against a fake engine: the request it sends and how it reads what Ollama answers. */
class OllamaLlmClientTest {

    private val config = OllamaConfig(baseUrl = "http://ollama.test:11434", model = "llama3.2:3b")

    private val json = Json

    /** A response shaped like a real, non-streaming Ollama `/api/chat` answer (extra fields included). */
    private fun ollamaAnswer(content: String): String = buildJsonObject {
        put("model", "llama3.2:3b")
        put("created_at", "2026-11-11T12:00:00.123456Z")
        put("message", buildJsonObject {
            put("role", "assistant")
            put("content", content)
        })
        put("done_reason", "stop")
        put("done", true)
        put("total_duration", 1_234_567_890)
        put("load_duration", 12_345_678)
        put("prompt_eval_count", 187)
        put("eval_count", 31)
    }.toString()

    private fun MockRequestHandleScope.jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    private fun clientWith(
        connectTimeoutMillis: Long = OLLAMA_CONNECT_TIMEOUT_MILLIS,
        requestTimeoutMillis: Long = OLLAMA_REQUEST_TIMEOUT_MILLIS,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = OllamaLlmClient(
        HttpClient(MockEngine(handler)) { configureOllama(connectTimeoutMillis, requestTimeoutMillis) },
        config,
    )

    private fun answering(content: String) = clientWith { jsonResponse(ollamaAnswer(content)) }

    // ---- the request ---------------------------------------------------------------------------

    @Test
    fun theRequestHasTheShapeOllamaExpects() = runBlocking {
        var request: HttpRequestData? = null
        var body: JsonObject? = null
        val client = clientWith {
            request = it
            body = json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
            jsonResponse(ollamaAnswer("""{"title":"Buy milk","description":""}"""))
        }

        client.parse("buy milk")

        assertEquals(HttpMethod.Post, request!!.method)
        assertEquals("http://ollama.test:11434/api/chat", request!!.url.toString())
        assertEquals(ContentType.Application.Json, request!!.body.contentType?.withoutParameters())
        val sent = body!!
        assertEquals("llama3.2:3b", sent["model"]!!.jsonPrimitive.content)
        assertEquals(false, sent["stream"]!!.jsonPrimitive.boolean)
        assertEquals(0, sent["options"]!!.jsonObject["temperature"]!!.jsonPrimitive.int)
    }

    @Test
    fun theFormatIsAJsonSchemaWithBothFieldsRequired() = runBlocking {
        var body: JsonObject? = null
        val client = clientWith {
            body = json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
            jsonResponse(ollamaAnswer("""{"title":"t","description":""}"""))
        }

        client.parse("x")

        val schema = body!!["format"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        val properties = schema["properties"]!!.jsonObject
        assertEquals(setOf("title", "description"), properties.keys)
        for (field in properties.values) assertEquals("string", field.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            setOf("title", "description"),
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
    }

    @Test
    fun theSystemPromptAndTheUserTextAreSentAsTheTwoMessages() = runBlocking {
        var messages: JsonArray? = null
        val client = clientWith {
            messages = json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject["messages"]!!.jsonArray
            jsonResponse(ollamaAnswer("""{"title":"t","description":""}"""))
        }

        client.parse("kup mleko, jutro rano")

        val (system, user) = messages!!.map { it.jsonObject }
        assertEquals("system", system["role"]!!.jsonPrimitive.content)
        assertEquals(PARSE_SYSTEM_PROMPT, system["content"]!!.jsonPrimitive.content)
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        assertEquals("kup mleko, jutro rano", user["content"]!!.jsonPrimitive.content)
        assertEquals(2, messages!!.size)
    }

    @Test
    fun theModelAndAddressComeFromTheConfig() = runBlocking {
        var url = ""
        var model = ""
        val client = OllamaLlmClient(
            HttpClient(
                MockEngine {
                    url = it.url.toString()
                    model = json.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject["model"]!!.jsonPrimitive.content
                    jsonResponse(ollamaAnswer("""{"title":"t","description":""}"""))
                },
            ) { configureOllama() },
            OllamaConfig(baseUrl = "http://host.docker.internal:11434", model = "qwen2.5:7b"),
        )

        client.parse("x")

        assertEquals("http://host.docker.internal:11434/api/chat", url)
        assertEquals("qwen2.5:7b", model)
    }

    // ---- the answer ----------------------------------------------------------------------------

    @Test
    fun aRealLookingAnswerIsParsedIntoADraft() = runBlocking {
        val client = answering("""{"title":"Buy milk and bread","description":"Tomorrow morning, before work"}""")

        assertEquals(
            ParsedTodoDto("Buy milk and bread", "Tomorrow morning, before work"),
            client.parse("uh, buy milk and bread, tomorrow morning before work"),
        )
    }

    @Test
    fun polishTextAndAnEmptyDescriptionComeThroughUnchanged() = runBlocking {
        val client = answering("""{"title":"Kup mleko 🥛","description":""}""")

        assertEquals(ParsedTodoDto("Kup mleko 🥛", ""), client.parse("kup mleko"))
    }

    @Test
    fun theClientDoesNotTrimOrCapTheModelsOutput() = runBlocking {
        // That is `polishDraft`'s job, once, for every implementation.
        val client = answering("""{"title":"  padded  ","description":" x "}""")

        assertEquals(ParsedTodoDto("  padded  ", " x "), client.parse("x"))
    }

    // ---- unusable output (502) -----------------------------------------------------------------

    @Test
    fun outputThatIsNotAJsonObjectForTheSchemaIsBadOutput() {
        val unusable = listOf(
            "Sure! Here is your task: buy milk",
            "",
            """["title","description"]""",
            """"just a string"""",
            """{"description":"no title"}""",
            """{"title":"no description"}""",
            """{"title":5,"description":""}""",
            """{"title":"t","description":null}""",
            """{"title":null,"description":""}""",
            """{"title":{"nested":"t"},"description":""}""",
        )
        for (content in unusable) {
            assertFailsWith<LlmBadOutputException>("content=$content") { runBlocking { answering(content).parse("x") } }
        }
    }

    @Test
    fun anAnswerWithoutAMessageIsBadOutput() {
        val envelopes = listOf(
            "not json at all",
            "{}",
            """{"message":"a string, not an object"}""",
            """{"message":{"role":"assistant"}}""",
            """{"message":{"content":5}}""",
        )
        for (envelope in envelopes) {
            val client = clientWith { jsonResponse(envelope) }
            assertFailsWith<LlmBadOutputException>("envelope=$envelope") { runBlocking { client.parse("x") } }
        }
    }

    // ---- unavailable (503) ---------------------------------------------------------------------

    @Test
    fun nothingListeningIsUnavailable() {
        val client = clientWith { throw ConnectException("Connection refused") }

        assertFailsWith<LlmUnavailableException> { runBlocking { client.parse("x") } }
    }

    @Test
    fun anErrorStatusFromOllamaIsUnavailable() {
        // E.g. the model has not been pulled: Ollama answers 404 with a JSON error.
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.InternalServerError, HttpStatusCode.ServiceUnavailable)) {
            val client = clientWith { jsonResponse("""{"error":"model 'llama3.2:3b' not found"}""", status) }
            assertFailsWith<LlmUnavailableException>("status=$status") { runBlocking { client.parse("x") } }
        }
    }

    @Test
    fun aModelThatNeverAnswersIsUnavailableAfterTheTimeout() {
        val client = clientWith(requestTimeoutMillis = 100) {
            delay(10_000)
            jsonResponse(ollamaAnswer("""{"title":"t","description":""}"""))
        }

        assertFailsWith<LlmUnavailableException> { runBlocking { client.parse("x") } }
    }

    @Test
    fun theRealTimeoutsAreTheOnesFromTheSpec() {
        assertEquals(30_000L, OLLAMA_REQUEST_TIMEOUT_MILLIS)
        assertEquals(5_000L, OLLAMA_CONNECT_TIMEOUT_MILLIS)
    }
}
