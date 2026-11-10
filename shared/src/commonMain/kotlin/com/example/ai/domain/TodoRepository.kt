package com.example.ai.domain

import kotlinx.coroutines.flow.Flow

interface TodoRepository {
    fun observeTodos(): Flow<List<Todo>>

    suspend fun getTodo(id: Long): Todo?

    /** Creates a not-done todo and returns its generated id. */
    suspend fun createTodo(title: String, description: String): Long

    suspend fun updateTodo(id: Long, title: String, description: String)

    suspend fun setDone(id: Long, isDone: Boolean)

    suspend fun deleteTodo(id: Long)
}
