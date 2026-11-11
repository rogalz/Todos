package com.example.ai.data

import com.example.ai.data.local.SyncStatus
import com.example.ai.data.local.TodoEntity
import com.example.ai.domain.Todo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class TodoMappersTest {

    @Test
    fun entityMapsToDomain() {
        val entity = TodoEntity(
            id = "7", title = "Title", description = "Desc", isDone = true,
            createdAt = 100, updatedAt = 200, deletedAt = null, syncStatus = SyncStatus.PENDING,
        )

        assertEquals(
            Todo(
                id = "7", title = "Title", description = "Desc", isDone = true,
                updatedAt = Instant.fromEpochMilliseconds(200), isSynced = false,
            ),
            entity.toDomain(),
        )
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

    // Stage 5 deliberately moved `updatedAt` and `isSynced` into the domain model (the "Edited ..."
    // footer and the "not synced yet" icon). What must still stay out is `createdAt` (list order only)
    // and `deletedAt` (tombstones are never listed).
    @Test
    fun createdAtAndDeletedAtDoNotLeakIntoTheDomainModel() {
        val alive = TodoEntity("1", "T", "D", false, createdAt = 1, updatedAt = 2, deletedAt = null, syncStatus = SyncStatus.SYNCED)
        val other = alive.copy(createdAt = 10, deletedAt = 30)

        assertEquals(alive.toDomain(), other.toDomain())
    }

    @Test
    fun updatedAtReachesTheDomainModel() {
        val entity = TodoEntity("1", "T", "D", false, createdAt = 1, updatedAt = 1_700_000_000_123, deletedAt = null, syncStatus = SyncStatus.SYNCED)

        assertEquals(Instant.fromEpochMilliseconds(1_700_000_000_123), entity.toDomain().updatedAt)
        assertEquals(
            Instant.fromEpochMilliseconds(5),
            entity.copy(updatedAt = 5).toDomain().updatedAt,
            "a different updatedAt must give a different domain value",
        )
    }

    @Test
    fun onlySyncedRowsAreMarkedAsSynced() {
        val entity = TodoEntity("1", "T", "D", false, createdAt = 1, updatedAt = 2, deletedAt = null, syncStatus = SyncStatus.SYNCED)

        assertTrue(entity.toDomain().isSynced)
        assertFalse(entity.copy(syncStatus = SyncStatus.PENDING).toDomain().isSynced)
    }

    @Test
    fun theTodosOwnDisplayValuesDoNotOverrideTheBookkeepingTheCallerStates() {
        val todo = Todo("1", "T", "", isDone = false, updatedAt = Instant.fromEpochMilliseconds(999), isSynced = true)

        val entity = todo.toEntity(createdAt = 1, updatedAt = 2, deletedAt = null, syncStatus = SyncStatus.PENDING)

        assertEquals(2, entity.updatedAt)
        assertEquals(SyncStatus.PENDING, entity.syncStatus)
    }
}
