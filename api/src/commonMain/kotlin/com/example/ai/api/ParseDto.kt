package com.example.ai.api

import kotlinx.serialization.Serializable

/**
 * `POST /parse` body: what the user said (or typed), as plain text.
 *
 * @property text must not be blank and, trimmed, at most [ApiRoutes.PARSE_MAX_TEXT_LENGTH] characters.
 */
@Serializable
data class ParseRequest(val text: String)

/**
 * `POST /parse` answer: a draft for a todo. It is only a suggestion; the app shows it to the
 * user, who edits and approves it before anything is stored.
 *
 * @property title short and imperative (the server caps it at [ApiRoutes.PARSE_MAX_TITLE_LENGTH]).
 * @property description the useful details; empty when the text had none.
 */
@Serializable
data class ParsedTodoDto(
    val title: String,
    val description: String,
)
