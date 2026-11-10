package com.example.ai.server

import com.example.ai.api.TodoDto
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Outcome of checking a request; [Invalid.reason] goes to the client as the 400 body. */
internal sealed interface Checked<out T> {
    data class Valid<out T>(val value: T) : Checked<T>
    data class Invalid(val reason: String) : Checked<Nothing>
}

/** `since` is optional (default 0 = everything); when present it must be a non-negative number. */
internal fun parseSince(raw: String?): Checked<Long> {
    if (raw == null) return Checked.Valid(0L)
    val since = raw.toLongOrNull()
    return if (since == null || since < 0) {
        Checked.Invalid("since must be a non-negative integer")
    } else {
        Checked.Valid(since)
    }
}

/**
 * Checks a `PUT /todos/{id}` request and returns the todo to store.
 *
 * Ids are compared as UUIDs and stored in the canonical lowercase form, so `ABC…` and `abc…` are
 * one todo and not two. Only the hyphenated 8-4-4-4-12 form is accepted, which is what the clients
 * generate. The returned todo carries the canonical id.
 *
 * A blank title is only an error on a live todo: a tombstone is a delete, and a delete should not
 * fail because of what the title looked like.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun validateUpsert(pathId: String, body: TodoDto): Checked<TodoDto> {
    val id = parseUuid(pathId) ?: return Checked.Invalid("id in the path is not a UUID")
    val bodyId = parseUuid(body.id)
    if (bodyId != id) return Checked.Invalid("id in the body must be the same UUID as the id in the path")
    if (body.deletedAt == null && body.title.isBlank()) return Checked.Invalid("title must not be blank")
    return Checked.Valid(body.copy(id = id.toString()))
}

@OptIn(ExperimentalUuidApi::class)
private fun parseUuid(raw: String): Uuid? =
    try {
        Uuid.parseHexDash(raw)
    } catch (_: IllegalArgumentException) {
        null
    }
