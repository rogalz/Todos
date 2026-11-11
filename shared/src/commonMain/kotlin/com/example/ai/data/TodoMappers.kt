package com.example.ai.data

import com.example.ai.api.TodoDto
import com.example.ai.data.local.SyncStatus
import com.example.ai.data.local.TodoEntity
import com.example.ai.domain.Todo
import kotlin.time.Instant

// The domain model carries only what the UI shows: `updatedAt` (the "Edited ..." footer) and
// `isSynced` (the "not synced yet" icon). `createdAt` (list order only) and `deletedAt` (tombstones are
// never listed) deliberately stay out.
fun TodoEntity.toDomain(): Todo = Todo(
    id = id,
    title = title,
    description = description,
    isDone = isDone,
    updatedAt = Instant.fromEpochMilliseconds(updatedAt),
    isSynced = syncStatus == SyncStatus.SYNCED,
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

// The caller states all bookkeeping explicitly instead of this function inventing values. That includes
// `updatedAt` and `syncStatus`: the todo's own `updatedAt` / `isSynced` are display values and are
// deliberately ignored here, so there is exactly one source for what gets stored.
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
