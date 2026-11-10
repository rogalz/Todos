package com.example.ai.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.local.SyncStatus
import com.example.ai.data.local.TodoEntity
import com.example.ai.data.local.rawRow
import com.example.ai.domain.Todo
import com.example.ai.testing.FakeClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Real repository on a real in-memory SQLite with a controllable clock. The clock is advanced
 * before every write under test, so a timestamp that a write forgot to refresh shows up as a
 * stale value.
 */
class RoomTodoRepositoryTest {

    private lateinit var database: AppDatabase
    private val clock = FakeClock()
    private lateinit var repository: RoomTodoRepository

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<AppDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        repository = RoomTodoRepository(database.todoDao(), clock)
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    /** A row that the server already has, so `PENDING` after a write can only come from the write. */
    private suspend fun seedSynced(id: String = "seed", title: String = "Title", description: String = "Desc"): TodoEntity {
        val entity = TodoEntity(
            id = id, title = title, description = description, isDone = false,
            createdAt = 100, updatedAt = 100, deletedAt = null, syncStatus = SyncStatus.SYNCED,
        )
        database.todoDao().insert(entity)
        return entity
    }

    @Test
    fun createTodoStoresAPendingRowStampedWithTheInjectedClock() = runTest {
        val id = repository.createTodo("Title", "Desc")

        val now = clock.currentMillis
        assertEquals(
            TodoEntity(
                id = id, title = "Title", description = "Desc", isDone = false,
                createdAt = now, updatedAt = now, deletedAt = null, syncStatus = SyncStatus.PENDING,
            ),
            database.rawRow(id),
        )
        assertEquals(Todo(id, "Title", "Desc", isDone = false), repository.getTodo(id))
    }

    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun createTodoGeneratesDistinctValidUuids() = runTest {
        val first = repository.createTodo("A", "")
        val second = repository.createTodo("B", "")

        assertNotEquals(first, second)
        // parse() throws on anything that is not a UUID; the round trip also pins the canonical form.
        assertEquals(first, Uuid.parse(first).toString())
        assertEquals(second, Uuid.parse(second).toString())
    }

    @Test
    fun updateTodoStampsUpdatedAtAndMarksPending() = runTest {
        val seeded = seedSynced()
        clock.advanceBy(500)

        repository.updateTodo(seeded.id, "New", "New desc")

        assertEquals(
            seeded.copy(title = "New", description = "New desc", updatedAt = clock.currentMillis, syncStatus = SyncStatus.PENDING),
            database.rawRow(seeded.id),
        )
    }

    @Test
    fun setDoneStampsUpdatedAtAndMarksPending() = runTest {
        val seeded = seedSynced()
        clock.advanceBy(500)

        repository.setDone(seeded.id, true)

        assertEquals(
            seeded.copy(isDone = true, updatedAt = clock.currentMillis, syncStatus = SyncStatus.PENDING),
            database.rawRow(seeded.id),
        )
    }

    @Test
    fun deleteTodoSoftDeletesAndMarksPending() = runTest {
        val seeded = seedSynced()
        clock.advanceBy(500)

        repository.deleteTodo(seeded.id)

        assertEquals(
            seeded.copy(deletedAt = clock.currentMillis, updatedAt = clock.currentMillis, syncStatus = SyncStatus.PENDING),
            database.rawRow(seeded.id),
        )
        assertNull(repository.getTodo(seeded.id))
        assertTrue(repository.observeTodos().first().isEmpty())
    }

    @Test
    fun deletingTwiceKeepsTheFirstTombstone() = runTest {
        val seeded = seedSynced()
        clock.advanceBy(500)
        repository.deleteTodo(seeded.id)
        val tombstone = database.rawRow(seeded.id)

        clock.advanceBy(500)
        repository.deleteTodo(seeded.id)

        assertEquals(tombstone, database.rawRow(seeded.id))
    }

    @Test
    fun editingOrTogglingADeletedTodoChangesNothing() = runTest {
        val seeded = seedSynced()
        clock.advanceBy(500)
        repository.deleteTodo(seeded.id)
        val tombstone = database.rawRow(seeded.id)

        clock.advanceBy(500)
        repository.updateTodo(seeded.id, "Changed", "changed")
        repository.setDone(seeded.id, true)

        assertEquals(tombstone, database.rawRow(seeded.id))
    }

    @Test
    fun listIsNewestFirstAndNotReorderedByLaterEdits() = runTest {
        val oldest = repository.createTodo("oldest", "")
        clock.advanceBy(10)
        repository.createTodo("middle", "")
        clock.advanceBy(10)
        repository.createTodo("newest", "")

        clock.advanceBy(10)
        repository.updateTodo(oldest, "oldest, edited", "")
        clock.advanceBy(10)
        repository.setDone(oldest, true)

        assertEquals(
            listOf("newest", "middle", "oldest, edited"),
            repository.observeTodos().first().map { it.title },
        )
    }
}
