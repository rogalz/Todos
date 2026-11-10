package com.example.ai.data.local

import androidx.room.useReaderConnection

/**
 * Reads a row exactly as stored, including soft-deleted ones that no DAO query returns. Lets tests
 * check tombstones without adding a test-only method to the production DAO.
 *
 * `SyncStatus.valueOf` throws on an unknown value, so this also fails if the literal written by
 * the DAO SQL ever stops matching an enum constant.
 */
suspend fun AppDatabase.rawRow(id: String): TodoEntity? = useReaderConnection { connection ->
    connection.usePrepared(
        "SELECT id, title, description, isDone, createdAt, updatedAt, deletedAt, syncStatus " +
            "FROM todos WHERE id = ?",
    ) { statement ->
        statement.bindText(1, id)
        if (!statement.step()) {
            null
        } else {
            TodoEntity(
                id = statement.getText(0),
                title = statement.getText(1),
                description = statement.getText(2),
                isDone = statement.getLong(3) != 0L,
                createdAt = statement.getLong(4),
                updatedAt = statement.getLong(5),
                deletedAt = if (statement.isNull(6)) null else statement.getLong(6),
                syncStatus = SyncStatus.valueOf(statement.getText(7)),
            )
        }
    }
}
