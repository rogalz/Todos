package com.example.ai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Every write stamps `updatedAt = :now` and `syncStatus = 'PENDING'` in the SQL itself, so a
 * caller can never forget to mark a row for upload. `now` is a parameter (not read here) so the
 * time source stays the injected `Clock`. 'PENDING' is a literal on purpose: it must match
 * [SyncStatus.PENDING]`.name`; the repository tests fail if the two ever drift apart.
 *
 * Soft-deleted rows (`deletedAt IS NOT NULL`) are invisible to every query here. All updates also
 * require `deletedAt IS NULL`, so a tombstone is never touched again: repeating a delete keeps the
 * original `deletedAt`, and an edit that races with a delete cannot bump the tombstone.
 */
@Dao
interface TodoDao {

    // Newest first, independent of the done state, so ticking a checkbox never moves an item.
    // `id DESC` only breaks ties (equal createdAt, e.g. same millisecond) so the order is
    // deterministic and rows never swap places between two emissions.
    @Query("SELECT * FROM todos WHERE deletedAt IS NULL ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): TodoEntity?

    @Insert
    suspend fun insert(entity: TodoEntity)

    // Deliberately does not touch `isDone`: editing text must never overwrite the checkbox state.
    @Query(
        "UPDATE todos SET title = :title, description = :description, " +
            "updatedAt = :now, syncStatus = 'PENDING' WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun updateContent(id: String, title: String, description: String, now: Long)

    @Query(
        "UPDATE todos SET isDone = :isDone, updatedAt = :now, syncStatus = 'PENDING' " +
            "WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun setDone(id: String, isDone: Boolean, now: Long)

    // Tombstone instead of DELETE: other devices only learn about the deletion from this row.
    @Query(
        "UPDATE todos SET deletedAt = :now, updatedAt = :now, syncStatus = 'PENDING' " +
            "WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun softDelete(id: String, now: Long)
}
