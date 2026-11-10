package com.example.ai.data

import com.example.ai.data.local.SyncStatus
import com.example.ai.data.local.TodoDao
import com.example.ai.data.local.TodoEntity
import com.example.ai.domain.Todo
import com.example.ai.domain.TodoRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** @param clock the only source of `now`, so tests control every timestamp that gets stored. */
class RoomTodoRepository(
    private val dao: TodoDao,
    private val clock: Clock,
) : TodoRepository {

    override fun observeTodos(): Flow<List<Todo>> =
        dao.observeAll().map { entities -> entities.map(TodoEntity::toDomain) }

    override suspend fun getTodo(id: String): Todo? = dao.getById(id)?.toDomain()

    // All writes are NonCancellable: a screen being popped (which cancels its ViewModel scope)
    // must never abort write that has already been requested.

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun createTodo(title: String, description: String): String =
        withContext(NonCancellable) {
            val now = now()
            val todo = Todo(id = Uuid.random().toString(), title = title, description = description, isDone = false)
            dao.insert(
                todo.toEntity(createdAt = now, updatedAt = now, deletedAt = null, syncStatus = SyncStatus.PENDING),
            )
            todo.id
        }

    override suspend fun updateTodo(id: String, title: String, description: String) =
        withContext(NonCancellable) { dao.updateContent(id, title, description, now()) }

    override suspend fun setDone(id: String, isDone: Boolean) =
        withContext(NonCancellable) { dao.setDone(id, isDone, now()) }

    // Soft delete: the row stays as a tombstone until the deletion has been synced.
    override suspend fun deleteTodo(id: String) =
        withContext(NonCancellable) { dao.softDelete(id, now()) }

    private fun now(): Long = clock.now().toEpochMilliseconds()
}
