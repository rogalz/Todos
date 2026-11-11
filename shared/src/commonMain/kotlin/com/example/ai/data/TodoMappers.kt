package com.example.ai.data

import com.example.ai.api.TodoDto
import com.example.ai.data.local.SyncStatus
import com.example.ai.data.local.TodoEntity
import com.example.ai.domain.Todo

// The domain model intentionally carries no timestamps or sync state (the UI does not need them).
fun TodoEntity.toDomain(): Todo = Todo(
    id = id,
    title = title,
    description = description,
    isDone = isDone,
)

// Wire format <-> row. `syncStatus` is local bookkeeping and never goes over the wire.
fun TodoEntity.toDto(): TodoDto = TodoDto(
    id = id,
    title = title,
    description = description,
    isDone = isDone,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

// What a todo received from the server looks like in Room: the server already has it, so SYNCED.
fun TodoDto.toEntity(syncStatus: SyncStatus = SyncStatus.SYNCED): TodoEntity = TodoEntity(
    id = id,
    title = title,
    description = description,
    isDone = isDone,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    syncStatus = syncStatus,
)

// The bookkeeping fields have no home in `Todo`, so the caller has to state them explicitly
// instead of this function inventing values.
fun Todo.toEntity(
    createdAt: Long,
    updatedAt: Long,
    deletedAt: Long?,
    syncStatus: SyncStatus,
): TodoEntity = TodoEntity(
    id = id,
    title = title,
    description = description,
    isDone = isDone,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    syncStatus = syncStatus,
)
