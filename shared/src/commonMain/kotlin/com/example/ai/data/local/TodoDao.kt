package com.example.ai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {

    // Newest first, independent of the done state, so ticking a checkbox never moves an item.
    @Query("SELECT * FROM todos ORDER BY id DESC")
    fun observeAll(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun getById(id: Long): TodoEntity?

    @Insert
    suspend fun insert(entity: TodoEntity): Long

    // Deliberately does not touch `isDone`: editing text must never overwrite the checkbox state.
    @Query("UPDATE todos SET title = :title, description = :description WHERE id = :id")
    suspend fun updateContent(id: Long, title: String, description: String)

    @Query("UPDATE todos SET isDone = :isDone WHERE id = :id")
    suspend fun setDone(id: Long, isDone: Boolean)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun deleteById(id: Long)
}
