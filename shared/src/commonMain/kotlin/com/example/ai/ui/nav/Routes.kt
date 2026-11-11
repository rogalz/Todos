package com.example.ai.ui.nav

import kotlinx.serialization.Serializable

@Serializable
data object TodoListRoute

/** @property todoId id of the todo to edit, `null` opens the screen in "new todo" mode. */
@Serializable
data class TodoDetailRoute(val todoId: String? = null)
