package com.example.ai.domain

data class Todo(
    val id: Long,
    val title: String,
    val description: String,
    val isDone: Boolean,
)
