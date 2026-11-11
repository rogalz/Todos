package com.example.ai.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs against a real in-memory SQLite (same bundled driver as production). */
class TodoDaoTest {

    private lateinit var database: AppDatabase
    private val dao: TodoDao get() = database.todoDao()

    // Each insert gets a later createdAt, like real creations do. Rows that must tie say so explicitly.
    private var nextCreatedAt = 1_000L
    private var nextId = 1

    @BeforeTest
    fun setUp() {
        nextCreatedAt = 1_000L
        nextId = 1
        database = Room.inMemoryDatabaseBuilder<AppDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    private fun entity(
        title: String,
        description: String = "",
        isDone: Boolean = false,
        id: String = "id-${nextId++}",
        createdAt: Long = nextCreatedAt++,
        syncStatus: SyncStatus = SyncStatus.SYNCED,
    ) = TodoEntity(
        id = id,
        title = title,
        description = description,
        isDone = isDone,
        createdAt = createdAt,
        updatedAt = createdAt,
        deletedAt = null,
        syncStatus = syncStatus,
    )

    private suspend fun insert(title: String, description: String = "", isDone: Boolean = false): String {
        val entity = entity(title, description, isDone)
        dao.insert(entity)
        return entity.id
    }

    @Test
    fun insertedRowsAreReadableUnderTheirOwnId() = runTest {
        val a = entity("A", "desc")
        val b = entity("B")
        dao.insert(a)
        dao.insert(b)

        assertNotEquals(a.id, b.id)
        assertEquals(a, dao.getById(a.id))
        assertEquals(b, dao.getById(b.id))
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

        // `now` is far later than both createdAt values: ordering by updatedAt would reorder here.
        dao.setDone(oldest, true, now = 999_999L)

        assertEquals(listOf("newest", "oldest"), dao.observeAll().first().map { it.title })
    }

    @Test
    fun rowsWithTheSameCreatedAtHaveAStableOrder() = runTest {
        dao.insert(entity("a", id = "a", createdAt = 5))
        dao.insert(entity("c", id = "c", createdAt = 5))
        dao.insert(entity("b", id = "b", createdAt = 5))

        val first = dao.observeAll().first().map { it.id }
        val second = dao.observeAll().first().map { it.id }

        assertEquals(listOf("c", "b", "a"), first)
        assertEquals(first, second)
    }

    @Test
    fun updateContentChangesTextButNotDoneState() = runTest {
        val id = insert("Old", "old desc", isDone = true)
        val before = dao.getById(id)!!

        dao.updateContent(id, "New", "new desc", now = 5_000L)

        assertEquals(
            before.copy(title = "New", description = "new desc", updatedAt = 5_000L, syncStatus = SyncStatus.PENDING),
            dao.getById(id),
        )
        assertEquals(true, dao.getById(id)?.isDone)
    }

    @Test
    fun setDoneTogglesBothWaysAndLeavesTextAlone() = runTest {
        val id = insert("Task", "desc")

        dao.setDone(id, true, now = 5_000L)
        assertEquals(true, dao.getById(id)?.isDone)

        dao.setDone(id, false, now = 6_000L)
        val stored = dao.getById(id)!!
        assertEquals("Task", stored.title)
        assertEquals("desc", stored.description)
        assertEquals(false, stored.isDone)
        assertEquals(6_000L, stored.updatedAt)
        assertEquals(SyncStatus.PENDING, stored.syncStatus)
    }

    @Test
    fun writesNeverChangeCreatedAt() = runTest {
        val id = insert("Task")
        val createdAt = dao.getById(id)!!.createdAt

        dao.updateContent(id, "T2", "d", now = 7_000L)
        dao.setDone(id, true, now = 8_000L)
        dao.softDelete(id, now = 9_000L)

        assertEquals(createdAt, database.rawRow(id)?.createdAt)
    }

    @Test
    fun softDeleteHidesOnlyTheGivenRow() = runTest {
        val keep = insert("keep")
        val remove = insert("remove")

        dao.softDelete(remove, now = 5_000L)

        assertNull(dao.getById(remove))
        assertEquals(listOf(keep), dao.observeAll().first().map { it.id })
    }

    @Test
    fun softDeleteKeepsTheRowAsATombstone() = runTest {
        val id = insert("Task", "desc", isDone = true)

        dao.softDelete(id, now = 5_000L)

        val tombstone = database.rawRow(id)
        assertEquals(5_000L, tombstone?.deletedAt)
        assertEquals(5_000L, tombstone?.updatedAt)
        assertEquals(SyncStatus.PENDING, tombstone?.syncStatus)
        // Content is untouched: only the markers change.
        assertEquals("Task", tombstone?.title)
        assertEquals("desc", tombstone?.description)
        assertEquals(true, tombstone?.isDone)
    }

    @Test
    fun repeatedSoftDeleteKeepsTheOriginalTombstone() = runTest {
        val id = insert("Task")

        dao.softDelete(id, now = 5_000L)
        dao.softDelete(id, now = 9_000L)

        val tombstone = database.rawRow(id)
        assertEquals(5_000L, tombstone?.deletedAt)
        assertEquals(5_000L, tombstone?.updatedAt)
    }

    @Test
    fun writesToATombstoneAreNoOps() = runTest {
        val id = insert("Task", "desc")
        dao.softDelete(id, now = 5_000L)
        val tombstone = database.rawRow(id)

        dao.updateContent(id, "Changed", "changed", now = 9_000L)
        dao.setDone(id, true, now = 9_000L)

        assertEquals(tombstone, database.rawRow(id))
    }

    @Test
    fun operationsOnMissingIdAreNoOps() = runTest {
        val id = insert("only")
        val before = dao.getById(id)

        dao.updateContent("missing", "x", "y", now = 5_000L)
        dao.setDone("missing", true, now = 5_000L)
        dao.softDelete("missing", now = 5_000L)

        assertNull(dao.getById("missing"))
        assertNull(database.rawRow("missing"))
        assertEquals(before, dao.getById(id))
    }

    @Test
    fun flowEmitsAgainAfterEveryWrite() = runTest {
        val id = insert("A")
        assertEquals(false, dao.observeAll().first().single().isDone)

        dao.setDone(id, true, now = 5_000L)

        assertEquals(true, dao.observeAll().first().single().isDone)
    }

    @Test
    fun flowDropsARowWhenItIsSoftDeleted() = runTest {
        val id = insert("A")
        assertEquals(1, dao.observeAll().first().size)

        dao.softDelete(id, now = 5_000L)

        assertTrue(dao.observeAll().first().isEmpty())
    }

    @Test
    fun textSurvivesUnicodeAndMultilineRoundTrip() = runTest {
        val id = insert("Zażółć gęślą jaźń 🚀", "linia 1\nlinia 2\n\ttab")

        val stored = dao.getById(id)

        assertEquals("Zażółć gęślą jaźń 🚀", stored?.title)
        assertEquals("linia 1\nlinia 2\n\ttab", stored?.description)
    }
}
