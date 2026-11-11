package com.example.ai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * @property id client-generated UUID, so a todo can be created offline without asking a server.
 * @property createdAt epoch ms; the only input of the list order (never changes after creation).
 * @property updatedAt epoch ms of the last local change.
 * @property deletedAt epoch ms when soft deleted, `null` while the todo is alive. The row is kept
 *   as a tombstone so the deletion can still be uploaded.
 */
@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val isDone: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val syncStatus: SyncStatus,
)
