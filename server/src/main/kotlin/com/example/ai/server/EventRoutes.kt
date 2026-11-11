package com.example.ai.server

import com.example.ai.api.ApiRoutes
import com.example.ai.api.ServerEvent
import io.ktor.server.routing.Route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription
import kotlinx.serialization.json.Json
import kotlin.time.Duration

// The `data:` of `hello` is JSON; plain defaults are enough, nothing here is read back.
private val eventJson = Json

/**
 * `GET /events`: tells a connected client that something changed. It never carries todo data, the
 * client pulls (see [ServerEvent]), so a lost or duplicated event is harmless.
 *
 * The connection ends when the client goes away. The heartbeat is what makes that visible to the
 * server: writing a comment every [heartbeatPeriod] fails once the peer is dead, which cancels this
 * handler, so a dead connection is never kept (and never leaks a subscriber) for longer than that.
 * It also keeps proxies and the client's idle timeout from dropping a quiet but healthy stream.
 */
fun Route.eventRoutes(store: TodoStore, heartbeatPeriod: Duration) {
    sse(ApiRoutes.EVENTS) {
        heartbeat { period = heartbeatPeriod }

        // `onSubscription` runs once the subscription is registered, so the revision for `hello` is
        // read AFTER we can no longer miss an event. The other way round, a write between "read the
        // revision" and "start listening" would be lost until the next write.
        store.events
            .onSubscription {
                val hello = ServerEvent.Hello(serverId = store.serverId, revision = store.currentRevision())
                send(event = ApiRoutes.EVENT_HELLO, data = eventJson.encodeToString(hello))
            }
            .collect { revision ->
                send(event = ApiRoutes.EVENT_CHANGED, data = revision.toString())
            }
    }
}
