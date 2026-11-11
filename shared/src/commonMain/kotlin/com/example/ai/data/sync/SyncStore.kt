package com.example.ai.data.sync

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.example.ai.api.ChangesResponse
import com.example.ai.api.TodoDto
import com.example.ai.data.local.SyncCursorEntity
import com.example.ai.data.local.SyncDao
import com.example.ai.data.local.SyncStatus
import com.example.ai.data.toDto
import com.example.ai.data.toEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Where this client stands with the server. `serverId = null, revision = 0` = never synced. */
data class SyncCursor(val serverId: String?, val revision: Long)

/**
 * The local side of the sync: every rule about how a version from the server meets the rows in
 * Room lives here, so there is exactly one place to read and test them. [SyncEngine] only decides
 * *when* to call these.
 *
 * ## Why the writes run in one immediate transaction
 *
 * Applying a remote version is read-compare-write. If a user edit could land between the read and
 * the write, an older remote version would overwrite the newer edit. The transaction runs on
 * Room's single writer connection and starts as `IMMEDIATE`, so the read is already inside the
 * write lock and every user write simply waits until the whole step is done. The same transaction
 * is what keeps items and cursor together: a failure while applying leaves neither.
 *
 * Nothing slow runs inside a transaction (no network): the engine calls the server first and
 * hands the answer to this class.
 *
 * ## The two rules
 *
 * *Pull* (a version arrived from the server): the remote version wins only if it is strictly
 * newer than the local row; on a tie the local row stays.
 *
 * *Push response* (the server answered a PUT of ours with its winning version): the server is the
 * arbiter, **including on a tie**. Under the pull rule a tie would keep our content and mark it
 * `SYNCED` while the server (and so every other client) holds different content, and the two
 * would never converge. An edit made while the push was in flight has a newer `updatedAt` than
 * the response and is kept `PENDING`.
 *
 * ## Known limit
 *
 * `updatedAt` is the only version marker. Two edits of one row in the very same millisecond, one of
 * them during a push, cannot be told apart by the `markSynced` guard (the same limit applies to
 * last-write-wins on the server). Realistic edits are far apart in time.
 *
 * @param dao taken separately from [database] so tests can wrap it to inject failures.
 */
class SyncStore(
    private val database: RoomDatabase,
    private val dao: SyncDao,
) {

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    /** Rows to upload, oldest change first, tombstones included. */
    suspend fun pendingTodos(): List<TodoDto> = dao.getPending().map { it.toDto() }

    suspend fun readCursor(): SyncCursor =
        dao.getCursor()?.let { SyncCursor(it.serverId, it.revision) } ?: SyncCursor(serverId = null, revision = 0)

    /**
     * Applies the server's answer to a push of [sent]. See the class comment for the rule.
     *
     * Cases, with `local` = the row as it is now (the user may have edited it during the push):
     *  - `response.updatedAt < local.updatedAt`: edited during the push, keep it `PENDING`.
     *  - the server stored exactly what we sent: `markSynced`, a single statement that checks
     *    `updatedAt` once more.
     *  - anything else (the server holds a newer version, or a different one on a tie): adopt it.
     */
    suspend fun applyPushResponse(sent: TodoDto, response: TodoDto) {
        check(response.id == sent.id) { "The server answered a push of ${sent.id} with ${response.id}" }
        inTransaction {
            val local = dao.getAnyById(sent.id)
            when {
                local == null -> dao.upsertRemote(response.toEntity(SyncStatus.SYNCED))
                response.updatedAt < local.updatedAt -> Unit
                response == sent -> dao.markSynced(sent.id, expectedUpdatedAt = sent.updatedAt)
                else -> dao.upsertRemote(response.toEntity(SyncStatus.SYNCED))
            }
        }
    }

    /**
     * Applies a pull answer: every item by the pull rule, then the new cursor, all or nothing.
     *
     * On `reset` the server lost its data (see `ChangesResponse`). Every local row is marked
     * `PENDING` **first** and the snapshot is applied afterwards, so rows that come back from the
     * server end up `SYNCED` and only rows the server does not have (or has in an older version)
     * stay `PENDING` to be re-uploaded. Marking *after* applying would upload the rows we just
     * received for nothing. `updatedAt` is untouched, so re-uploaded rows cannot beat newer edits.
     */
    suspend fun applyPull(response: ChangesResponse) {
        inTransaction {
            if (response.reset) dao.markAllPending()
            for (remote in response.items) {
                val local = dao.getAnyById(remote.id)
                if (local == null || remote.updatedAt > local.updatedAt) {
                    dao.upsertRemote(remote.toEntity(SyncStatus.SYNCED))
                }
            }
            dao.saveCursor(SyncCursorEntity(serverId = response.serverId, revision = response.revision))
        }
    }

    // NonCancellable like the repository writes: the answer from the server has already been
    // received, so a cancellation right now must not throw it away half-way. The transaction itself
    // is atomic either way, this only avoids pointless repeated pulls.
    private suspend fun inTransaction(block: suspend () -> Unit) =
        withContext(NonCancellable) {
            database.useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
        }
}
