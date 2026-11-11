package com.example.ai.data.remote

import com.example.ai.api.ApiRoutes
import com.example.ai.api.ServerEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.sse.sse
import io.ktor.client.plugins.timeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException

/**
 * @param client injected, so tests can pass the client of Ktor's `testApplication`. It must be
 *   configured with [configureTodoClient] (which installs the SSE plugin).
 * @param baseUrl e.g. `http://localhost:8080`; a trailing slash is fine.
 * @param socketTimeoutMillis how long the stream may stay completely silent before it counts as dead.
 *   See [DEFAULT_SSE_SOCKET_TIMEOUT_MILLIS] for why it is set explicitly.
 * @param log where warnings go; a parameter so tests can read them.
 */
class KtorTodoEvents(
    private val client: HttpClient,
    baseUrl: String,
    private val socketTimeoutMillis: Long = DEFAULT_SSE_SOCKET_TIMEOUT_MILLIS,
    private val log: (String) -> Unit = { println("KtorTodoEvents: $it") },
) : TodoEvents {

    private val baseUrl = baseUrl.trimEnd('/')

    override fun events(): Flow<ServerEvent> = flow {
        client.sse(
            urlString = "$baseUrl${ApiRoutes.EVENTS}",
            request = {
                timeout {
                    // The stream is long-lived by design, so the 15 s request timeout of ordinary calls must
                    // not apply. (Ktor already exempts SSE from it; stated here so that this does not depend
                    // on that detail.) The connect timeout stays the client's.
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    // This one is what actually governs an idle stream, see DEFAULT_SSE_SOCKET_TIMEOUT_MILLIS.
                    this.socketTimeoutMillis = this@KtorTodoEvents.socketTimeoutMillis
                }
            },
        ) {
            // Heartbeat comments are not shown by the plugin, only real events reach this loop.
            incoming.collect { sse ->
                val event = parseServerEvent(sse.event, sse.data)
                when {
                    event != null -> emit(event)
                    // An unknown name is a newer server's business and silently fine; a KNOWN event that
                    // cannot be read is a bug worth a line in the log (a lost `hello` means no catch-up sync).
                    sse.event == ApiRoutes.EVENT_HELLO || sse.event == ApiRoutes.EVENT_CHANGED ->
                        log("ignored '${sse.event}' event with unreadable data: ${sse.data}")
                }
            }
        }
    }
}

/**
 * Wire format → [ServerEvent], or `null` for something this client does not understand: an event name
 * it does not know (tolerant reader: a newer server may add events) or data that cannot be read.
 * Never throws, so one odd event cannot kill a healthy stream.
 *
 * `changed` carries the bare revision (see [ServerEvent.Changed]), `hello` is JSON.
 */
internal fun parseServerEvent(name: String?, data: String?): ServerEvent? {
    if (data == null) return null
    return when (name) {
        ApiRoutes.EVENT_HELLO -> try {
            TodoJson.decodeFromString<ServerEvent.Hello>(data)
        } catch (e: SerializationException) {
            null
        }
        ApiRoutes.EVENT_CHANGED -> data.trim().toLongOrNull()?.let(ServerEvent::Changed)
        else -> null
    }
}

/**
 * The server sends a heartbeat every 15 s, so a healthy stream is never silent for longer than that.
 * Three missed heartbeats (45 s) count as a dead connection.
 *
 * This has to be set explicitly, for two reasons. The request timeout is not the only timeout that can
 * cut an idle stream: engines have their own idle limits, and OkHttp's default read timeout is 10 s,
 * shorter than the heartbeat, so without this the stream would be cut and re-established forever.
 * And the client has no other way to notice a half-open connection (a network that vanished without a
 * FIN); the server only notices a dead peer when one of its writes fails.
 */
const val DEFAULT_SSE_SOCKET_TIMEOUT_MILLIS = 45_000L
