package com.example.ai.data

import com.example.ai.api.TodoDto
import com.example.ai.data.local.SyncStatus
import com.example.ai.data.local.TodoEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TodoDtoMappersTest {

    private val entity = TodoEntity(
        id = "2d9a3a0e-1111-4222-8333-444455556666",
        title = "Zażółć 🚀",
        description = "line 1\nline 2",
        isDone = true,
        createdAt = 100,
        updatedAt = 250,
        deletedAt = null,
        syncStatus = SyncStatus.PENDING,
    )

    @Test
    fun entityToDtoCopiesEveryWireField() {
        val dto = entity.toDto()

        assertEquals(
            TodoDto(
                id = entity.id,
                title = "Zażółć 🚀",
                description = "line 1\nline 2",
                isDone = true,
                createdAt = 100,
                updatedAt = 250,
                deletedAt = null,
            ),
            dto,
        )
    }

    @Test
    fun tombstoneKeepsItsDeletedAtOnTheWire() {
        val dto = entity.copy(deletedAt = 300).toDto()

        assertEquals(300L, dto.deletedAt)
    }

    @Test
    fun dtoToEntityIsSyncedByDefaultBecauseTheServerAlreadyHasIt() {
        val roundTripped = entity.toDto().toEntity()

        assertEquals(entity.copy(syncStatus = SyncStatus.SYNCED), roundTripped)
    }

    @Test
    fun dtoToEntityTakesAnExplicitStatus() {
        assertEquals(SyncStatus.PENDING, entity.toDto().toEntity(SyncStatus.PENDING).syncStatus)
    }

    @Test
    fun liveDtoHasNoDeletedAt() {
        assertNull(entity.toDto().toEntity().deletedAt)
    }
}
