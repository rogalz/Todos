package com.example.ai.ui.nav

import kotlinx.serialization.Serializable

@Serializable
data object TodoListRoute

/**
 * @property todoId id of the todo to edit, `null` opens the screen in "new todo" mode.
 * @property dictatedText a sentence the user spoke or typed: the screen then opens as a draft, which
 *   the AI fills in and the user must save explicitly. Only meaningful together with a `null` [todoId].
 */
@Serializable
data class TodoDetailRoute(val todoId: String? = null, val dictatedText: String? = null)
