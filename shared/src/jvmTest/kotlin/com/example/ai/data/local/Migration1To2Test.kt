package com.example.ai.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Replays the committed schemas (`shared/schemas`): a real v1 database is created from `1.json`,
 * filled with data, migrated, and validated against `2.json` by Room itself.
 */
class Migration1To2Test {

    private val migrationTime = 1_700_000_000_000L
    private val directory: Path = Files.createTempDirectory("migration-test")
    private val databasePath: Path = directory / "todo.db"

    @get:Rule
    val helper = MigrationTestHelper(
        // The working directory of a Gradle test task is the module directory (shared/).
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

    private class V1Row(val id: Long, val title: String, val description: String = "", val isDone: Boolean = false)

    private fun createV1Database(vararg rows: V1Row) {
        val connection = helper.createDatabase(1)
        rows.forEach { row ->
            connection.prepare("INSERT INTO todos (id, title, description, isDone) VALUES (?, ?, ?, ?)").use {
                it.bindLong(1, row.id)
                it.bindText(2, row.title)
                it.bindText(3, row.description)
                it.bindLong(4, if (row.isDone) 1 else 0)
                it.step()
            }
        }
        connection.close()
    }

    /** Runs the migration and, as a side effect, lets Room validate the result against 2.json. */
    private fun migrate(): List<TodoEntity> {
        val connection = helper.runMigrationsAndValidate(2, listOf(Migration1To2(FakeClock(migrationTime))))
        return connection.use { it.readAllRows() }
    }

    @Test
    fun contentAndDoneStateArePreserved() {
        createV1Database(
            V1Row(1, "Buy milk", "2 liters", isDone = true),
            V1Row(2, "Zażółć gęślą jaźń 🚀", "linia 1\nlinia 2\n\ttab", isDone = false),
        )

        val migrated = migrate()

        assertEquals(
            setOf(
                Triple("Buy milk", "2 liters", true),
                Triple("Zażółć gęślą jaźń 🚀", "linia 1\nlinia 2\n\ttab", false),
            ),
            migrated.map { Triple(it.title, it.description, it.isDone) }.toSet(),
        )
        assertEquals(2, migrated.size)
    }

    @Test
    fun orderIsPreservedAndFollowsTheDocumentedFormula() {
        createV1Database(V1Row(1, "oldest"), V1Row(2, "middle"), V1Row(3, "newest"))

        val migrated = migrate()

        // Old order was `id DESC`; the new order is `createdAt DESC`.
        assertEquals(listOf("newest", "middle", "oldest"), migrated.sortedByDescending { it.createdAt }.map { it.title })
        val createdAtByTitle = migrated.associate { it.title to it.createdAt }
        assertEquals(migrationTime, createdAtByTitle["newest"])
        assertEquals(migrationTime - 1, createdAtByTitle["middle"])
        assertEquals(migrationTime - 2, createdAtByTitle["oldest"])
    }

    @Test
    fun gapsInTheOldIdsKeepTheOrder() {
        // Ids 3, 4, 6, 7 were deleted at some point.
        createV1Database(V1Row(2, "a"), V1Row(5, "b"), V1Row(9, "c"))

        val migrated = migrate()

        assertEquals(listOf("c", "b", "a"), migrated.sortedByDescending { it.createdAt }.map { it.title })
        assertEquals(3, migrated.map { it.createdAt }.toSet().size, "distinct ids must give distinct createdAt")
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun idsAreUniqueValidUuids() {
        createV1Database(*Array(50) { V1Row(it + 1L, "todo $it") })

        val ids = migrate().map { it.id }

        assertEquals(50, ids.toSet().size)
        // parse() throws on anything that is not a UUID; the round trip pins the canonical form.
        ids.forEach { assertEquals(it, Uuid.parse(it).toString()) }
    }

    @Test
    fun everyRowIsPendingAndLiveWithMatchingTimestamps() {
        createV1Database(V1Row(1, "a"), V1Row(2, "b", isDone = true))

        val migrated = migrate()

        assertTrue(migrated.isNotEmpty())
        migrated.forEach {
            assertEquals(SyncStatus.PENDING, it.syncStatus)
            assertNull(it.deletedAt)
            assertEquals(it.createdAt, it.updatedAt)
        }
    }

    @Test
    fun emptyDatabaseMigratesToAnEmptyTable() {
        createV1Database()

        assertTrue(migrate().isEmpty())
    }

    // ---- the real wiring (getRoomDatabase), not just the Migration class --------------------

    @Test
    fun productionDatabaseFactoryMigratesAnExistingFile() = runTest {
        createV1Database(V1Row(1, "oldest", isDone = true), V1Row(2, "newest", "desc"))
        val database = getRoomDatabase(Room.databaseBuilder<AppDatabase>(name = databasePath.toString()), FakeClock(migrationTime))

        try {
            val todos = database.todoDao().observeAll().first()

            assertEquals(listOf("newest", "oldest"), todos.map { it.title })
            assertEquals(listOf("desc", ""), todos.map { it.description })
            assertEquals(listOf(false, true), todos.map { it.isDone })
        } finally {
            database.close()
        }
    }

    @Test
    fun aDatabaseWithoutAMigrationPathFailsInsteadOfBeingWiped() = runTest {
        // Simulates a database written by a newer app version: there is no migration path back.
        val connection = helper.createDatabase(2)
        connection.prepare(
            "INSERT INTO todos (id, title, description, isDone, createdAt, updatedAt, deletedAt, syncStatus) " +
                "VALUES ('keep-me', 'Precious', '', 0, 1, 1, NULL, 'SYNCED')",
        ).use { it.step() }
        connection.execSQL("PRAGMA user_version = 99")
        connection.close()
        val database = getRoomDatabase(Room.databaseBuilder<AppDatabase>(name = databasePath.toString()), FakeClock(migrationTime))

        try {
            assertFailsWith<Exception> { database.todoDao().observeAll().first() }
        } finally {
            database.close()
        }

        val survivor = BundledSQLiteDriver().open(databasePath.toString())
        try {
            assertEquals(listOf("Precious"), survivor.readAllRows().map { it.title })
        } finally {
            survivor.close()
        }
    }

    @Test
    fun aMigrationThatFailsHalfwayLeavesTheOldDatabaseUntouched() = runTest {
        createV1Database(V1Row(1, "first"), V1Row(2, "second"))
        // A view over `todos` makes the final `ALTER TABLE todos_new RENAME TO todos` fail, i.e.
        // after the new table was created, filled and the old one dropped.
        BundledSQLiteDriver().open(databasePath.toString()).use {
            it.execSQL("CREATE VIEW stale_view AS SELECT id FROM todos")
        }
        // Through the production factory on purpose: the transaction around a migration is Room's
        // own behaviour, which the test helper does not necessarily reproduce.
        val database = getRoomDatabase(Room.databaseBuilder<AppDatabase>(name = databasePath.toString()), FakeClock(migrationTime))

        try {
            assertFailsWith<Exception> { database.todoDao().observeAll().first() }
        } finally {
            database.close()
        }

        val connection = BundledSQLiteDriver().open(databasePath.toString())
        try {
            assertEquals(1L, connection.queryLong("PRAGMA user_version"))
            assertEquals(2L, connection.queryLong("SELECT COUNT(*) FROM todos"))
            assertEquals(0L, connection.queryLong("SELECT COUNT(*) FROM sqlite_master WHERE name = 'todos_new'"))
        } finally {
            connection.close()
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun SQLiteConnection.queryLong(sql: String): Long =
        prepare(sql).use { statement ->
            check(statement.step())
            statement.getLong(0)
        }

    private fun SQLiteConnection.readAllRows(): List<TodoEntity> =
        prepare(
            "SELECT id, title, description, isDone, createdAt, updatedAt, deletedAt, syncStatus FROM todos " +
                "ORDER BY createdAt DESC",
        ).use { statement ->
            buildList {
                while (statement.step()) {
                    add(
                        TodoEntity(
                            id = statement.getText(0),
                            title = statement.getText(1),
                            description = statement.getText(2),
                            isDone = statement.getLong(3) != 0L,
                            createdAt = statement.getLong(4),
                            updatedAt = statement.getLong(5),
                            deletedAt = if (statement.isNull(6)) null else statement.getLong(6),
                            syncStatus = SyncStatus.valueOf(statement.getText(7)),
                        ),
                    )
                }
            }
        }
}
