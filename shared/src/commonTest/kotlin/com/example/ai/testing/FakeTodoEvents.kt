package com.example.ai.testing

import com.example.ai.api.ServerEvent
import com.example.ai.data.remote.TodoEvents
import com.example.ai.data.sync.AppForeground
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * The event stream as a script. Every collection of [events] is a [Connection] that the test drives:
 * it sends events, fails it or ends it, and can see when the collector went away (cancellation).
 *
 * @param now the test's clock (virtual time), to record when each connection was opened.
 */
class FakeTodoEvents(private val now: () -> Long = { 0L }) : TodoEvents {

    class Connection internal constructor(val number: Int, val openedAt: Long) {
        internal sealed interface Item {
            class Event(val event: ServerEvent) : Item
            class Failure(val cause: Throwable) : Item
            data object End : Item
        }

        internal val items = Channel<Item>(Channel.UNLIMITED)

        /** `true` while someone collects this connection; `false` once the collector was cancelled or it ended. */
        var collecting = false
            internal set

        fun send(event: ServerEvent) {
            items.trySend(Item.Event(event))
        }

        /** The connection breaks: the collector gets [cause]. */
        fun fail(cause: Throwable = Exception("connection lost")) {
            items.trySend(Item.Failure(cause))
        }

        /** The server ends the stream normally: the collector completes. */
        fun close() {
            items.trySend(Item.End)
        }
    }

    val connections = mutableListOf<Connection>()

    /** Runs when a connection is opened, before anything is delivered: script it to hello, fail, ... */
    var onConnect: (Connection) -> Unit = {}

    val activeConnections: Int get() = connections.count { it.collecting }

    override fun events(): Flow<ServerEvent> = flow {
        val connection = Connection(number = connections.size + 1, openedAt = now())
        connections += connection
        connection.collecting = true
        try {
            onConnect(connection)
            for (item in connection.items) {
                when (item) {
                    is Connection.Item.Event -> emit(item.event)
                    is Connection.Item.Failure -> throw item.cause
                    Connection.Item.End -> return@flow
                }
            }
        } finally {
            connection.collecting = false
        }
    }
}

/** A foreground signal the test flips. */
class FakeAppForeground(initial: Boolean = true) : AppForeground {
    private val state = MutableStateFlow(initial)
    override val isForeground: StateFlow<Boolean> = state

    fun set(value: Boolean) {
        state.value = value
    }
}
