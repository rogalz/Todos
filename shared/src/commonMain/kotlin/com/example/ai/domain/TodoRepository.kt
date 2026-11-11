package com.example.ai.domain

import kotlinx.coroutines.flow.Flow

interface TodoRepository {
    fun observeTodos(): Flow<List<Todo>>

    suspend fun getTodo(id: String): Todo?

    /** Creates a not-done todo and returns its generated id (a client-side UUID). */
    suspend fun createTodo(title: String, description: String): String

    suspend fun updateTodo(id: String, title: String, description: String)

    suspend fun setDone(id: String, isDone: Boolean)

    suspend fun deleteTodo(id: String)
}
