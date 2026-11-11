package com.example.ai.data.sync

import com.example.ai.data.remote.ApiException
import com.example.ai.data.remote.TodoApi
import com.example.ai.util.runSuspendCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Moves changes between Room and the server: upload what is `PENDING`, then apply what is new on
 * the server. It knows nothing about the UI; the UI only sees the effect, through Room.
 *
 * All rules about how a version meets a local row live in [SyncStore]; this class only orders the
 * steps and decides what a failure means.
 *
 * ## Single flight
 *
 * One sync at a time (a [Mutex]). A call that arrives while a sync is running waits and then runs
 * a sync of its own, because the running one may already have read the pending rows before the
 * caller's change was written. The second run usually finds little to do (the first one made the
 * rows `SYNCED`), which is what keeps the same version from being pushed twice.
 *
 * ## Failures
 *
 * - Network error, timeout, 5xx, 408, 429: the sync stops. Rows pushed so far stay `SYNCED`, the
 *   rest stay `PENDING`, the cursor is unchanged. Nothing is lost, the next sync continues.
 * - Any other 4xx on a row (see [ApiException.isRejection]): the server refuses that row and always
 *   will, so it is logged and skipped. Stopping would let one bad row block every row behind it
 *   and the pull. The sync still ends as a [SyncResult.Failure], so it is visible and retried.
 * - Cancellation is rethrown (never reported as a failure), as everywhere in this code base.
 *
 * @param log where warnings go; a parameter so tests can read them. Defaults to the console, which
 *   ends up in Logcat on Android.
 */
class SyncEngine(
    private val api: TodoApi,
    private val store: SyncStore,
    private val clock: Clock,
    private val log: (String) -> Unit = { println("SyncEngine: $it") },
) {

    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    suspend fun sync(): SyncResult = mutex.withLock {
        _state.value = SyncState.Syncing
        var result: SyncResult? = null
        try {
            runSync().also { result = it }
        } finally {
            // `result` is still null when the sync was cancelled: that is not a failure, so no Failed.
            _state.value = if (result is SyncResult.Failure) SyncState.Failed(clock.now()) else SyncState.Idle
        }
    }

    private suspend fun runSync(): SyncResult {
        // Rows the server refused, by id: a second push after a reset must not list a row twice.
        val rejections = LinkedHashMap<String, ApiException>()

        val outcome = runSuspendCatching {
            push(rejections)
            val reset = pull()
            // The server lost its data and every local row was marked PENDING: rebuild it from here.
            if (reset) push(rejections)
        }

        outcome.exceptionOrNull()?.let { error ->
            log("sync stopped: $error")
            return SyncResult.Failure(error, rejections.keys.toList())
        }
        val firstRejection = rejections.values.firstOrNull() ?: return SyncResult.Success
        return SyncResult.Failure(firstRejection, rejections.keys.toList())
    }

    private suspend fun push(rejections: MutableMap<String, ApiException>) {
        for (sent in store.pendingTodos()) {
            val response = try {
                api.push(sent)
            } catch (e: ApiException) {
                if (!e.isRejection) throw e
                log("server rejected todo ${sent.id} with HTTP ${e.status}, skipping it for now: ${e.body}")
                rejections[sent.id] = e
                continue
            }
            store.applyPushResponse(sent, response)
        }
    }

    /** @return whether the server asked for a reset. */
    private suspend fun pull(): Boolean {
        val cursor = store.readCursor()
        val response = api.pull(since = cursor.revision, serverId = cursor.serverId)
        store.applyPull(response)
        return response.reset
    }
}
