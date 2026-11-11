package com.example.ai.data

import com.example.ai.data.local.SyncStatus
import com.example.ai.data.local.TodoEntity
import com.example.ai.domain.Todo
import kotlin.test.Test
import kotlin.test.assertEquals

class TodoMappersTest {

    @Test
    fun entityMapsToDomain() {
        val entity = TodoEntity(
            id = "7", title = "Title", description = "Desc", isDone = true,
            createdAt = 100, updatedAt = 200, deletedAt = null, syncStatus = SyncStatus.PENDING,
        )

        assertEquals(Todo(id = "7", title = "Title", description = "Desc", isDone = true), entity.toDomain())
    }

    @Test
    fun domainMapsToEntity() {
        val todo = Todo(id = "3", title = "T", description = "", isDone = false)

        assertEquals(
            TodoEntity(
                id = "3", title = "T", description = "", isDone = false,
                createdAt = 100, updatedAt = 200, deletedAt = null, syncStatus = SyncStatus.PENDING,
            ),
            todo.toEntity(createdAt = 100, updatedAt = 200, deletedAt = null, syncStatus = SyncStatus.PENDING),
        )
    }

    @Test
    fun roundTripKeepsEveryField() {
        val entity = TodoEntity(
            id = "5f0c3a52-8f4e-4c36-9d0e-0a1f6f1f2b11", title = "Zażółć gęślą jaźń", description = "line1\nline2",
            isDone = true, createdAt = 100, updatedAt = 200, deletedAt = null, syncStatus = SyncStatus.SYNCED,
        )

        assertEquals(
            entity,
            entity.toDomain().toEntity(
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                deletedAt = entity.deletedAt,
                syncStatus = entity.syncStatus,
            ),
        )
    }

    @Test
    fun bookkeepingFieldsDoNotLeakIntoTheDomainModel() {
        val alive = TodoEntity("1", "T", "D", false, createdAt = 1, updatedAt = 2, deletedAt = null, syncStatus = SyncStatus.SYNCED)
        val other = alive.copy(createdAt = 10, updatedAt = 20, syncStatus = SyncStatus.PENDING)

        assertEquals(alive.toDomain(), other.toDomain())
    }
}
