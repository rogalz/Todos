package com.example.ai.data.remote

import com.example.ai.api.ServerEvent
import kotlinx.coroutines.flow.Flow

/**
 * The server's "something changed" signal, as the live-updates code sees it. The events carry no todo
 * data; they only say when to pull (see [ServerEvent]).
 */
interface TodoEvents {

    /**
     * One connection to the server's event stream. **Cold**: it connects when collected and
     * disconnects when the collector is cancelled; every collection is a new connection that starts
     * with a [ServerEvent.Hello].
     *
     * The flow ends by throwing when the connection fails (nothing listening, a non-2xx answer, a
     * dead connection) and by completing normally when the server closes the stream. A dead
     * connection (silent past the socket timeout) is not reliably told apart from a close: Ktor's
     * SSE session turns a cancellation into a normal end, so it can arrive either way. A caller that
     * wants to stay connected must treat both as "disconnected" and reconnect; this class never
     * retries.
     */
    fun events(): Flow<ServerEvent>
}
