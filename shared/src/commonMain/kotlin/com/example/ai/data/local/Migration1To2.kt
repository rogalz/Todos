package com.example.ai.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * v1 → v2: `Long` auto-increment id → client-generated UUID, plus `createdAt`, `updatedAt`,
 * `deletedAt` and `syncStatus`.
 *
 * SQLite cannot change a primary key type, so the table is rebuilt: create `todos_new`, copy,
 * drop `todos`, rename. Room runs a migration inside one transaction, so a failure at any point
 * leaves the v1 database untouched.
 *
 * Rows are copied one by one in Kotlin because SQLite has no UUID function, and a random hex
 * string would not be a valid UUID.
 *
 * Edge cases:
 * - Empty table: nothing to copy, the new table is simply created empty.
 * - Gaps in the old ids (deleted rows): harmless. Only the relative order of the ids matters, and
 *   distinct ids always give distinct `createdAt` values; the gap only widens the spacing.
 * - The old order was `id DESC` (newest first). `createdAt = now - (maxOldId - oldId)` keeps that
 *   order under the new `createdAt DESC`. The newest migrated row gets `createdAt = now`, so any
 *   todo created after the migration sorts above it.
 * - Every migrated row is `PENDING`: the server has never seen it, so the first sync uploads it.
 *
 * @param clock source of the migration time (injected like everywhere else, so tests control it).
 */
internal class Migration1To2(private val clock: Clock) : Migration(1, 2) {

    private class OldRow(val id: Long, val title: String, val description: String, val isDone: Long)

    @OptIn(ExperimentalUuidApi::class)
    override fun migrate(connection: SQLiteConnection) {
        val now = clock.now().toEpochMilliseconds()

        val oldRows = buildList {
            connection.prepare("SELECT id, title, description, isDone FROM todos").use { select ->
                while (select.step()) {
                    add(OldRow(select.getLong(0), select.getText(1), select.getText(2), select.getLong(3)))
                }
            }
        }
        val maxOldId = oldRows.maxOfOrNull { it.id }

        // Same definition Room exports for v2 (shared/schemas/.../2.json); Room validates the
        // result against it after the migration.
        connection.execSQL(
            "CREATE TABLE `todos_new` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`description` TEXT NOT NULL, `isDone` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `syncStatus` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )

        if (maxOldId != null) {
            connection.prepare(
                "INSERT INTO `todos_new` (id, title, description, isDone, createdAt, updatedAt, deletedAt, syncStatus) " +
                    "VALUES (?, ?, ?, ?, ?, ?, NULL, '${SyncStatus.PENDING.name}')",
            ).use { insert ->
                for (row in oldRows) {
                    val createdAt = now - (maxOldId - row.id)
                    insert.bindText(1, Uuid.random().toString())
                    insert.bindText(2, row.title)
                    insert.bindText(3, row.description)
                    insert.bindLong(4, row.isDone)
                    insert.bindLong(5, createdAt)
                    insert.bindLong(6, createdAt) // updatedAt = createdAt
                    insert.step()
                    insert.reset()
                }
            }
        }

        connection.execSQL("DROP TABLE `todos`")
        connection.execSQL("ALTER TABLE `todos_new` RENAME TO `todos`")
    }
}
