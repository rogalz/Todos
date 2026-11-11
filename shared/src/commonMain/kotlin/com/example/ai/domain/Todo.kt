package com.example.ai.domain

import kotlin.time.Instant

/**
 * What the UI shows of a todo. Persistence bookkeeping stays out (`createdAt` only orders the list,
 * `deletedAt` is never visible because tombstones are not listed), except the two things stage 5 shows.
 *
 * The defaults exist so that tests which only care about the text do not have to state them. The
 * mapper in `data` always sets both explicitly (its tests lock that), so production code never
 * relies on a default.
 *
 * @property updatedAt when the todo was last changed on this device or pulled from the server.
 * @property isSynced `false` while a local change has not reached the server yet.
 */
data class Todo(
    val id: String,
    val title: String,
    val description: String,
    val isDone: Boolean,
    val updatedAt: Instant = Instant.fromEpochMilliseconds(0),
    val isSynced: Boolean = true,
)
