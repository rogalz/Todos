package com.example.ai.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs against a real in-memory SQLite (same bundled driver as production). */
class TodoDaoTest {

    private lateinit var database: AppDatabase
    private val dao: TodoDao get() = database.todoDao()

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<AppDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    private suspend fun insert(title: String, description: String = "", isDone: Boolean = false): Long =
        dao.insert(TodoEntity(title = title, description = description, isDone = isDone))

    @Test
    fun insertGeneratesIncreasingIdsAndRowIsReadable() = runTest {
        val first = insert("A", "desc")
        val second = insert("B")

        assertTrue(second > first)
        assertEquals(TodoEntity(first, "A", "desc", false), dao.getById(first))
    }

    @Test
    fun observeAllIsEmptyInitially() = runTest {
        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun observeAllReturnsNewestFirst() = runTest {
        insert("oldest")
        insert("middle")
        insert("newest")

        assertEquals(listOf("newest", "middle", "oldest"), dao.observeAll().first().map { it.title })
    }

    @Test
    fun observeAllKeepsOrderWhenDoneStateChanges() = runTest {
        val oldest = insert("oldest")
        insert("newest")

        dao.setDone(oldest, true)

        assertEquals(listOf("newest", "oldest"), dao.observeAll().first().map { it.title })
    }

    @Test
    fun updateContentChangesTextButNotDoneState() = runTest {
        val id = insert("Old", "old desc", isDone = true)

        dao.updateContent(id, "New", "new desc")

        assertEquals(TodoEntity(id, "New", "new desc", isDone = true), dao.getById(id))
    }

    @Test
    fun setDoneTogglesBothWaysAndLeavesTextAlone() = runTest {
        val id = insert("Task", "desc")

        dao.setDone(id, true)
        assertEquals(true, dao.getById(id)?.isDone)

        dao.setDone(id, false)
        assertEquals(TodoEntity(id, "Task", "desc", false), dao.getById(id))
    }

    @Test
    fun deleteRemovesOnlyTheGivenRow() = runTest {
        val keep = insert("keep")
        val remove = insert("remove")

        dao.deleteById(remove)

        assertNull(dao.getById(remove))
        assertEquals(listOf(keep), dao.observeAll().first().map { it.id })
    }

    @Test
    fun operationsOnMissingIdAreNoOps() = runTest {
        val id = insert("only")

        dao.updateContent(id + 100, "x", "y")
        dao.setDone(id + 100, true)
        dao.deleteById(id + 100)

        assertNull(dao.getById(id + 100))
        assertEquals(TodoEntity(id, "only", "", false), dao.getById(id))
    }

    @Test
    fun flowEmitsAgainAfterEveryWrite() = runTest {
        val id = insert("A")
        assertEquals(false, dao.observeAll().first().single().isDone)

        dao.setDone(id, true)

        assertEquals(true, dao.observeAll().first().single().isDone)
    }

    @Test
    fun textSurvivesUnicodeAndMultilineRoundTrip() = runTest {
        val id = insert("Zażółć gęślą jaźń 🚀", "linia 1\nlinia 2\n\ttab")

        val stored = dao.getById(id)

        assertEquals("Zażółć gęślą jaźń 🚀", stored?.title)
        assertEquals("linia 1\nlinia 2\n\ttab", stored?.description)
    }
}
