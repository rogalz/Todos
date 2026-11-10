package com.example.ai.data

import com.example.ai.data.local.TodoEntity
import com.example.ai.domain.Todo
import kotlin.test.Test
import kotlin.test.assertEquals

class TodoMappersTest {

    @Test
    fun entityMapsToDomain() {
        val entity = TodoEntity(id = 7, title = "Title", description = "Desc", isDone = true)

        assertEquals(Todo(id = 7, title = "Title", description = "Desc", isDone = true), entity.toDomain())
    }

    @Test
    fun domainMapsToEntity() {
        val todo = Todo(id = 3, title = "T", description = "", isDone = false)

        assertEquals(TodoEntity(id = 3, title = "T", description = "", isDone = false), todo.toEntity())
    }

    @Test
    fun roundTripKeepsEveryField() {
        val entity = TodoEntity(id = 42, title = "Zażółć gęślą jaźń", description = "line1\nline2", isDone = true)

        assertEquals(entity, entity.toDomain().toEntity())
    }
}
