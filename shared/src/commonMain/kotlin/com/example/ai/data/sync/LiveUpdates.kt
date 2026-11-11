package com.example.ai.data.sync

import com.example.ai.api.ServerEvent
import com.example.ai.data.remote.TodoEvents
import com.example.ai.util.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Whether the live-updates stream is up; see [LiveUpdates.connection]. */
enum class LiveConnection {
    /** No connection has been attempted yet (app start, or the app just came back to the foreground). */
    Unknown,

    /** The current connection delivered its `hello`. */
    Connected,

    /** The last attempt failed or the stream ended, and the next `hello` has not arrived yet. */
    Disconnected,
}

/**
 * Turns the server's "something changed" stream into syncs, so a change made on another device shows
 * up here without a restart or a local edit. It never touches data: the events only say *when* to
 * run the existing sync (push + pull), which writes to Room, which the UI observes. A missed or
 * duplicated event therefore costs nothing but a sync, and correctness still comes from pull.
 *
 * ## What it does with the stream
 *
 *  - `hello` (first event of every connection; we may have missed changes while disconnected):
 *    [serverIsReachable] (the server just answered, so the scheduler drops its failure backoff and
 *    syncs now).
 *  - `changed`: [requestSync], but only when the revision is news. A revision at or below the stored
 *    cursor is already pulled, which is what happens after our own push (the echo of our own change).
 *
 * `changed` carries only a revision, so "the server" is the one named in the `hello` of the *same
 * connection*. A cursor of a different server is worthless (after a restart the revision counter
 * starts again at 1, so `changed 3` can be news to a client whose cursor says 50): that always syncs,
 * and so does a `changed` before any `hello` and one whose cursor cannot be read.
 *
 * ## Connection lifetime
 *
 * The stream is open only while [AppForeground] says so; going to the background cancels it,
 * coming back reconnects (and the `hello` catches up). Any end of a connection (an error, or the
 * server closing it normally) is "disconnected" and reconnects after a backoff of 1, 2, 4, 8, then
 * 10 s ([MAX_RECONNECT_BACKOFF_MILLIS]; an attempt is cheap and recovery should be quick, so the cap
 * is lower than the scheduler's). A `hello` counts as "connected" and resets the backoff. There is
 * deliberately no minimum uptime: a server that accepts and then drops every connection is retried
 * every second, which is cheap enough not to deserve more logic.
 *
 * ## Observable connection state
 *
 * [connection] only *reports* the above (the sync status chip reads it); it changes no behaviour. It is
 * [LiveConnection.Unknown] until the first attempt has an outcome, so the app never claims to be offline
 * merely because the stream has not had time to connect. A `hello` makes it [LiveConnection.Connected],
 * any end of a connection [LiveConnection.Disconnected]. A change of the foreground state starts over at
 * [LiveConnection.Unknown]: a connection we closed on purpose is not a failure.
 *
 * @param readCursor the stored sync cursor.
 * @param requestSync [SyncScheduler.requestSync].
 * @param serverIsReachable [SyncScheduler.serverIsReachable].
 * @param scope the app-lifetime scope the connection lives in.
 * @param log where reconnect notes go; a parameter so tests can read them.
 */
class LiveUpdates(
    private val events: TodoEvents,
    private val foreground: AppForeground,
    private val readCursor: suspend () -> SyncCursor,
    private val requestSync: () -> Unit,
    private val serverIsReachable: () -> Unit,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = { println("LiveUpdates: $it") },
) {

    private var job: Job? = null

    private val _connection = MutableStateFlow(LiveConnection.Unknown)

    /** Whether the stream is up right now. Reporting only; see the class comment. */
    val connection: StateFlow<LiveConnection> = _connection.asStateFlow()

    /** Starts following [AppForeground]. Calling it again does nothing. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            // collectLatest: a change of the foreground state cancels the connection that is running.
            foreground.isForeground.collectLatest { inForeground ->
                // Whatever the old connection was, closing it because the app left the screen is not a failure.
                _connection.value = LiveConnection.Unknown
                if (inForeground) stayConnected()
            }
        }
    }

    /** Connects, and reconnects whenever the connection ends, until cancelled. Never returns normally. */
    private suspend fun stayConnected() {
        var failures = 0
        while (true) {
            // The server of THIS connection, from its hello; see the class comment.
            var connectionServerId: String? = null

            val outcome = runSuspendCatching {
                events.events().collect { event ->
                    when (event) {
                        is ServerEvent.Hello -> {
                            failures = 0
                            connectionServerId = event.serverId
                            _connection.value = LiveConnection.Connected
                            serverIsReachable()
                        }
                        is ServerEvent.Changed -> if (isNews(event.revision, connectionServerId)) requestSync()
                    }
                }
            }

            // Reached only when the stream ended by itself: a cancellation (background) throws out of
            // runSuspendCatching above and never gets here.
            _connection.value = LiveConnection.Disconnected
            failures += 1
            val wait = SyncScheduler.backoffMillis(failures, MAX_RECONNECT_BACKOFF_MILLIS)
            val why = outcome.exceptionOrNull()?.let { "failed: $it" } ?: "closed by the server"
            log("the event stream $why; reconnecting in $wait ms")
            delay(wait)
        }
    }

    private suspend fun isNews(revision: Long, connectionServerId: String?): Boolean {
        // Cannot compare without the connection's server: syncing is always safe.
        if (connectionServerId == null) return true
        val cursor = runSuspendCatching { readCursor() }.getOrNull() ?: return true
        return cursor.serverId != connectionServerId || revision > cursor.revision
    }

    companion object {
        const val MAX_RECONNECT_BACKOFF_MILLIS = 10_000L
    }
}
