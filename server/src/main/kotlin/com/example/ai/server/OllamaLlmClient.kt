package com.example.ai.server

import com.example.ai.api.ParsedTodoDto
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.http.content.TextContent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.time.TimeSource

/**
 * Where Ollama listens and which model to ask. Both come from the environment, so the same jar
 * runs against the Mac's Ollama directly (`:server:run`) and from Docker (`OLLAMA_URL` points at the host).
 */
data class OllamaConfig(val baseUrl: String, val model: String) {
    companion object {
        const val DEFAULT_URL = "http://localhost:11434"
        const val DEFAULT_MODEL = "llama3.2:3b"

        /** Unset or blank means the default, like `PORT`. A trailing slash on the URL is dropped. */
        fun fromEnv(env: (String) -> String? = { System.getenv(it) }): OllamaConfig = OllamaConfig(
            baseUrl = (env("OLLAMA_URL")?.trim()?.ifEmpty { null } ?: DEFAULT_URL).trimEnd('/'),
            model = env("OLLAMA_MODEL")?.trim()?.ifEmpty { null } ?: DEFAULT_MODEL,
        )
    }
}

/** A dead host should fail fast; the whole call has more room because a cold model takes seconds to load. */
internal const val OLLAMA_CONNECT_TIMEOUT_MILLIS = 5_000L
internal const val OLLAMA_REQUEST_TIMEOUT_MILLIS = 30_000L

/**
 * The one place that configures the client for Ollama, used by [OllamaLlmClient.create] and by the
 * tests, so they run with the real timeouts (the parameters let a test use a short one).
 */
internal fun HttpClientConfig<*>.configureOllama(
    connectTimeoutMillis: Long = OLLAMA_CONNECT_TIMEOUT_MILLIS,
    requestTimeoutMillis: Long = OLLAMA_REQUEST_TIMEOUT_MILLIS,
) {
    install(HttpTimeout) {
        this.connectTimeoutMillis = connectTimeoutMillis
        this.requestTimeoutMillis = requestTimeoutMillis
    }
}

/**
 * [LlmClient] backed by Ollama's `/api/chat` (non-streaming, temperature 0, answer constrained to a
 * JSON schema by Ollama's structured outputs).
 *
 * JSON is built and read by hand (not with `@Serializable` classes): the body is tiny, and this
 * keeps the serialization plugin out of the server module.
 *
 * @param http must be configured with [configureOllama]; injected so tests can pass a `MockEngine`.
 */
class OllamaLlmClient(
    private val http: HttpClient,
    private val config: OllamaConfig,
) : LlmClient, AutoCloseable {

    private val log = LoggerFactory.getLogger(OllamaLlmClient::class.java)

    override suspend fun parse(text: String): ParsedTodoDto {
        val started = TimeSource.Monotonic.markNow()
        var outcome = "error"
        try {
            val body = try {
                val response = http.post("${config.baseUrl}/api/chat") {
                    setBody(TextContent(requestBody(text).toString(), ContentType.Application.Json))
                }
                val answer = response.bodyAsText()
                // The model not being there (404), or Ollama failing (500), means "no LLM for you right
                // now", the same as nobody listening: the client falls back to the raw words either way.
                if (!response.status.isSuccess()) {
                    log.warn("Ollama answered {}: {}", response.status.value, answer.take(MAX_LOGGED_BODY))
                    throw LlmUnavailableException("Ollama answered HTTP ${response.status.value}")
                }
                answer
            } catch (e: IOException) {
                // Connection refused, DNS failure, connect or request timeout: all subclasses of IOException.
                throw LlmUnavailableException("Ollama is not reachable at ${config.baseUrl}: ${e.message}", e)
            }
            val draft = readDraft(body)
            outcome = "ok"
            return draft
        } finally {
            log.info(
                "parse model={} prompt=v{} outcome={} latencyMs={}",
                config.model, PROMPT_VERSION, outcome, started.elapsedNow().inWholeMilliseconds,
            )
        }
    }

    private fun requestBody(text: String): JsonObject = buildJsonObject {
        put("model", config.model)
        put("stream", false)
        putJsonObject("options") { put("temperature", 0) }
        put("format", DRAFT_SCHEMA)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", PARSE_SYSTEM_PROMPT)
            }
            addJsonObject {
                put("role", "user")
                put("content", text)
            }
        }
    }

    /**
     * Ollama's answer carries the model's output as a JSON *string* in `message.content`, which is
     * itself parsed here. Anything that is not an object with a string `title` and a string
     * `description` is [LlmBadOutputException].
     */
    private fun readDraft(ollamaBody: String): ParsedTodoDto {
        val content = (asObject(ollamaBody)?.get("message") as? JsonObject)?.get("content").asText()
            ?: throw LlmBadOutputException("Ollama's answer has no message.content")
        val draft = asObject(content) ?: throw LlmBadOutputException("the model's output is not a JSON object")
        val title = draft["title"].asText() ?: throw LlmBadOutputException("the model's output has no string title")
        val description = draft["description"].asText()
            ?: throw LlmBadOutputException("the model's output has no string description")
        return ParsedTodoDto(title = title, description = description)
    }

    private fun asObject(json: String): JsonObject? =
        try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (_: SerializationException) {
            null
        }

    private fun kotlinx.serialization.json.JsonElement?.asText(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    override fun close() = http.close()

    companion object {
        private const val MAX_LOGGED_BODY = 300

        fun create(config: OllamaConfig): OllamaLlmClient =
            OllamaLlmClient(HttpClient(OkHttp) { configureOllama() }, config)

        /** The shape Ollama is told to produce (`format`); both fields are required. */
        private val DRAFT_SCHEMA = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
                putJsonObject("description") { put("type", "string") }
            }
            putJsonArray("required") {
                add("title")
                add("description")
            }
        }
    }
}
