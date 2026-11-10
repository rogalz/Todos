package com.example.ai.testing

import com.example.ai.domain.Todo
import com.example.ai.domain.TodoRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

class FakeTodoRepository(initial: List<Todo> = emptyList()) : TodoRepository {

    data class Created(val title: String, val description: String)
    data class Updated(val id: Long, val title: String, val description: String)

    val todos = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    var failReads = false
    var failWrites = false

    /** When set, [getTodo] suspends until it is completed, to simulate a slow load. */
    var getTodoGate: CompletableDeferred<Unit>? = null

    val created = mutableListOf<Created>()
    val updated = mutableListOf<Updated>()
    val doneChanges = mutableListOf<Pair<Long, Boolean>>()
    val deleted = mutableListOf<Long>()

    val writeCount: Int get() = created.size + updated.size + doneChanges.size + deleted.size

    override fun observeTodos(): Flow<List<Todo>> =
        if (failReads) flow { throw IllegalStateException("read failed") } else todos

    override suspend fun getTodo(id: Long): Todo? {
        getTodoGate?.await()
        if (failReads) throw IllegalStateException("read failed")
        return todos.value.firstOrNull { it.id == id }
    }

    override suspend fun createTodo(title: String, description: String): Long {
        failIfRequested()
        val id = nextId++
        created += Created(title, description)
        todos.value = listOf(Todo(id, title, description, isDone = false)) + todos.value
        return id
    }

    override suspend fun updateTodo(id: Long, title: String, description: String) {
        failIfRequested()
        updated += Updated(id, title, description)
        todos.value = todos.value.map {
            // Like the real DAO query: never touches isDone.
            if (it.id == id) it.copy(title = title, description = description) else it
        }
    }

    override suspend fun setDone(id: Long, isDone: Boolean) {
        failIfRequested()
        doneChanges += id to isDone
        todos.value = todos.value.map { if (it.id == id) it.copy(isDone = isDone) else it }
    }

    override suspend fun deleteTodo(id: Long) {
        failIfRequested()
        deleted += id
        todos.value = todos.value.filterNot { it.id == id }
    }

    private fun failIfRequested() {
        if (failWrites) throw IllegalStateException("write failed")
    }
}
