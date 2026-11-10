package com.example.ai.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiContractTest {

    // Plain default Json on purpose: that is what a client gets without any configuration.
    private val json = Json

    private val alive = TodoDto(
        id = "5f0c3a52-8f4e-4c36-9d0e-0a1f6f1f2b11",
        title = "Zażółć gęślą jaźń 🚀",
        description = "line1\nline2 \"quoted\"",
        isDone = true,
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_000_500,
        deletedAt = null,
    )
    private val tombstone = alive.copy(deletedAt = 1_700_000_001_000, updatedAt = 1_700_000_001_000)

    @Test
    fun todoDtoRoundTripKeepsEveryField() {
        for (dto in listOf(alive, tombstone)) {
            assertEquals(dto, json.decodeFromString<TodoDto>(json.encodeToString(dto)))
        }
    }

    @Test
    fun changesResponseRoundTripKeepsEveryField() {
        val responses = listOf(
            ChangesResponse(items = emptyList(), revision = 0, serverId = "s-1", reset = false),
            ChangesResponse(items = listOf(alive, tombstone), revision = Long.MAX_VALUE, serverId = "s-2", reset = true),
        )
        for (response in responses) {
            assertEquals(response, json.decodeFromString<ChangesResponse>(json.encodeToString(response)))
        }
    }

    // The field names are the contract. A round trip alone would still pass if a field were renamed on
    // both sides at once, so the wire format is pinned here: changing it must be a deliberate edit.
    @Test
    fun todoDtoWireFormatIsPinned() {
        val dto = TodoDto(
            id = "id-1", title = "t", description = "d", isDone = false,
            createdAt = 1, updatedAt = 2, deletedAt = null,
        )

        assertEquals(
            """{"id":"id-1","title":"t","description":"d","isDone":false,"createdAt":1,"updatedAt":2,"deletedAt":null}""",
            json.encodeToString(dto),
        )
    }

    @Test
    fun changesResponseWireFormatIsPinned() {
        val response = ChangesResponse(items = emptyList(), revision = 7, serverId = "srv", reset = true)

        assertEquals(
            """{"items":[],"revision":7,"serverId":"srv","reset":true}""",
            json.encodeToString(response),
        )
    }

    @Test
    fun routesArePinned() {
        assertEquals("/health", ApiRoutes.HEALTH)
        assertEquals("/todos", ApiRoutes.TODOS)
        assertEquals("/todos/{id}", ApiRoutes.TODO_BY_ID)
        assertEquals("since", ApiRoutes.PARAM_SINCE)
        assertEquals("serverId", ApiRoutes.PARAM_SERVER_ID)
        assertEquals("/todos/abc", ApiRoutes.todoPath("abc"))
    }
}
