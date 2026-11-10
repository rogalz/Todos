package com.example.ai.data

import com.example.ai.data.local.TodoDao
import com.example.ai.data.local.TodoEntity
import com.example.ai.domain.Todo
import com.example.ai.domain.TodoRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomTodoRepository(private val dao: TodoDao) : TodoRepository {

    override fun observeTodos(): Flow<List<Todo>> =
        dao.observeAll().map { entities -> entities.map(TodoEntity::toDomain) }

    override suspend fun getTodo(id: Long): Todo? = dao.getById(id)?.toDomain()

    // All writes are NonCancellable: a screen being popped (which cancels its ViewModel scope)
    // must never abort write that has already been requested.

    override suspend fun createTodo(title: String, description: String): Long =
        withContext(NonCancellable) {
            dao.insert(TodoEntity(title = title, description = description, isDone = false))
        }

    override suspend fun updateTodo(id: Long, title: String, description: String) =
        withContext(NonCancellable) { dao.updateContent(id, title, description) }

    override suspend fun setDone(id: Long, isDone: Boolean) =
        withContext(NonCancellable) { dao.setDone(id, isDone) }

    override suspend fun deleteTodo(id: Long) =
        withContext(NonCancellable) { dao.deleteById(id) }
}
