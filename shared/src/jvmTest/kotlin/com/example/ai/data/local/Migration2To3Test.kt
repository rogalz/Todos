package com.example.ai.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.ai.testing.FakeClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * v2 -> v3 is a Room `AutoMigration` (only adds `sync_state`). The test replays the committed
 * schemas: a real v2 database is created from `2.json`, filled, migrated and validated against
 * `3.json` by Room itself.
 */
class Migration2To3Test {

    private val directory: Path = Files.createTempDirectory("migration-test-2-3")
    private val databasePath: Path = directory / "todo.db"

    @get:Rule
    val helper = MigrationTestHelper(
        schemaDirectoryPath = Path("schemas"),
        databasePath = databasePath,
        driver = BundledSQLiteDriver(),
        databaseClass = AppDatabase::class,
        databaseFactory = { AppDatabaseConstructor.initialize() },
    )

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    private fun createV2Database() {
        val connection = helper.createDatabase(2)
        // One live and one soft-deleted row, both PENDING / SYNCED mixed, to prove nothing is touched.
        connection.prepare(
            "INSERT INTO todos (id, title, description, isDone, createdAt, updatedAt, deletedAt, syncStatus) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { insert ->
            insert.bindText(1, "live-id")
            insert.bindText(2, "Buy milk 🥛")
            insert.bindText(3, "2 liters")
            insert.bindLong(4, 1)
            insert.bindLong(5, 100)
            insert.bindLong(6, 150)
            insert.bindNull(7)
            insert.bindText(8, "PENDING")
            insert.step()
            insert.reset()

            insert.bindText(1, "dead-id")
            insert.bindText(2, "Gone")
            insert.bindText(3, "")
            insert.bindLong(4, 0)
            insert.bindLong(5, 200)
            insert.bindLong(6, 300)
            insert.bindLong(7, 300)
            insert.bindText(8, "SYNCED")
            insert.step()
        }
        connection.close()
    }

    private fun SQLiteConnection.queryLong(sql: String): Long =
        prepare(sql).use { statement ->
            check(statement.step())
            statement.getLong(0)
        }

    @Test
    fun existingRowsSurviveAndTheSyncStateTableStartsEmpty() {
        createV2Database()

        // No explicit migrations: the AutoMigration has to be found by the helper through the database class.
        val connection = helper.runMigrationsAndValidate(3, emptyList())

        connection.use {
            assertEquals(2L, it.queryLong("SELECT COUNT(*) FROM todos"))
            assertEquals(150L, it.queryLong("SELECT updatedAt FROM todos WHERE id = 'live-id'"))
            assertEquals(300L, it.queryLong("SELECT deletedAt FROM todos WHERE id = 'dead-id'"))
            assertEquals(0L, it.queryLong("SELECT COUNT(*) FROM sync_state"))
        }
    }

    // ---- the real wiring (getRoomDatabase), not just the helper -----------------------------

    @Test
    fun productionDatabaseFactoryMigratesAV2FileAndReadsAnEmptyCursor() = runTest {
        createV2Database()
        val database = getRoomDatabase(Room.databaseBuilder<AppDatabase>(name = databasePath.toString()), FakeClock())

        try {
            // `observeAll` hides the tombstone, `getAnyById` does not; both work on the migrated file.
            assertEquals(listOf("live-id"), database.todoDao().observeAll().first().map { it.id })
            assertEquals(300L, database.syncDao().getAnyById("dead-id")?.deletedAt)
            // No row = never synced.
            assertNull(database.syncDao().getCursor())
        } finally {
            database.close()
        }
    }
}
