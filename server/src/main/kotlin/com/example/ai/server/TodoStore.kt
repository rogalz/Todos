package com.example.ai.server

import com.example.ai.api.ChangesResponse
import com.example.ai.api.TodoDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * In-memory todo storage: a map guarded by one [Mutex], plus a global revision counter.
 *
 * One lock for everything is deliberate. Every operation is a few map accesses, so contention is
 * irrelevant at this scale, and a single lock makes the two invariants below easy to see:
 *  - a write and its revision bump are one atomic step, so revisions are unique and gapless;
 *  - a pull reads the items and the revision in one step, so the cursor it returns is never
 *    ahead of (it would skip changes) or behind (it would repeat them) the items it returned.
 *
 * Nothing survives a restart. That is what [serverId] is for: it is new on every start, and a
 * client that holds a different one learns (via `reset`) that its cursor refers to a lost state.
 *
 * Time never enters here: `updatedAt` comes from the clients and is only compared, never read
 * from a clock, so there is nothing to inject.
 */
@OptIn(ExperimentalUuidApi::class)
class TodoStore(
    /** Injectable so tests can simulate a restart; a random UUID otherwise. */
    val serverId: String = Uuid.random().toString(),
) {
    private val mutex = Mutex()
    private val todos = HashMap<String, Stored>()
    private var revision = 0L

    /** The revision is stored with the todo: that is what `since` is compared against. */
    private class Stored(val dto: TodoDto, val revision: Long)

    /**
     * Last-write-wins upsert. Applies [incoming] only if nothing is stored for its id or it is
     * strictly newer by `updatedAt`; on a tie the stored version stays (see the spec). Returns the
     * winning version either way so the caller can reconcile. A write that changes nothing does not
     * bump the revision, which is what makes retries harmless.
     *
     * Tombstones are ordinary versions here: a newer live version replaces a tombstone and the
     * other way round.
     */
    suspend fun upsert(incoming: TodoDto): TodoDto = mutex.withLock {
        val stored = todos[incoming.id]
        if (stored != null && incoming.updatedAt <= stored.dto.updatedAt) {
            return@withLock stored.dto
        }
        revision += 1
        todos[incoming.id] = Stored(incoming, revision)
        incoming
    }

    /**
     * Changes for a pull.
     *
     * - [clientServerId] is `null` (first pull) or equals [serverId]: the todos stored after
     *   revision [since], `reset = false`.
     * - [clientServerId] differs: the server was restarted since the client's last pull, so
     *   [since] refers to a counter that no longer exists. It is ignored and everything is
     *   returned with `reset = true`.
     *
     * Items are ordered by revision so the order is deterministic.
     */
    suspend fun changes(since: Long, clientServerId: String?): ChangesResponse = mutex.withLock {
        val restarted = clientServerId != null && clientServerId != serverId
        val items = todos.values
            .filter { restarted || it.revision > since }
            .sortedBy { it.revision }
            .map { it.dto }
        ChangesResponse(items = items, revision = revision, serverId = serverId, reset = restarted)
    }
}
