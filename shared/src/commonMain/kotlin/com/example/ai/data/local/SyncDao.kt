package com.example.ai.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Queries for the sync engine only. The UI never sees this DAO: [TodoDao] keeps its guarantee that
 * soft-deleted rows are invisible, while everything here deliberately includes tombstones.
 *
 * Unlike [TodoDao], these writes do **not** stamp `updatedAt`. `updatedAt` is the version of a
 * todo, set by whoever edited it; sync only moves versions around and must never invent one.
 * 'PENDING' / 'SYNCED' are literals for the same reason as in [TodoDao]: they must match
 * [SyncStatus]`.name`.
 *
 * A read-compare-write sequence (applying a remote version) is only safe inside one write
 * transaction; the callers of this DAO take care of that, see `SyncStore`.
 */
@Dao
interface SyncDao {

    // Oldest change first, so edits reach the server in the order they were made; `id` only makes
    // the order deterministic.
    @Query("SELECT * FROM todos WHERE syncStatus = 'PENDING' ORDER BY updatedAt ASC, id ASC")
    suspend fun getPending(): List<TodoEntity>

    @Query("SELECT COUNT(*) FROM todos WHERE syncStatus = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    /** Like [TodoDao.getById], but a tombstone is returned too. */
    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun getAnyById(id: String): TodoEntity?

    /**
     * Marks a pushed row `SYNCED`, but only if it still has the `updatedAt` that was sent. If the
     * user edited the row while the push was in flight, `updatedAt` differs, nothing is updated and
     * the row stays `PENDING`, so the newer edit is pushed by the next sync.
     *
     * @return the number of updated rows (0 = the row changed in the meantime).
     */
    @Query("UPDATE todos SET syncStatus = 'SYNCED' WHERE id = :id AND updatedAt = :expectedUpdatedAt")
    suspend fun markSynced(id: String, expectedUpdatedAt: Long): Int

    /**
     * Writes a version that came from the server (insert or replace, tombstones and resurrections
     * included). The caller passes [SyncStatus.SYNCED] and has already decided that this version
     * wins; unlike the user's writes this may touch a tombstone, on purpose.
     */
    @Upsert
    suspend fun upsertRemote(entity: TodoEntity)

    /**
     * After a server reset every local row must be uploaded again. `updatedAt` is left alone: a
     * bumped timestamp would let the re-uploaded rows beat newer edits made on other devices.
     */
    @Query("UPDATE todos SET syncStatus = 'PENDING'")
    suspend fun markAllPending()

    @Query("SELECT * FROM sync_state WHERE id = ${SyncCursorEntity.SINGLE_ROW_ID}")
    suspend fun getCursor(): SyncCursorEntity?

    @Upsert
    suspend fun saveCursor(cursor: SyncCursorEntity)
}
