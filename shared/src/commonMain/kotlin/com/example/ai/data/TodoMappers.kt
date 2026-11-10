package com.example.ai.data

import com.example.ai.data.local.TodoEntity
import com.example.ai.domain.Todo

fun TodoEntity.toDomain(): Todo = Todo(
    id = id,
    title = title,
    description = description,
    isDone = isDone,
)

fun Todo.toEntity(): TodoEntity = TodoEntity(
    id = id,
    title = title,
    description = description,
    isDone = isDone,
)
