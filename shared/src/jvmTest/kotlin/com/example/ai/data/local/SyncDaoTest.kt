package com.example.ai.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Runs against a real in-memory SQLite (same bundled driver as production). */
class SyncDaoTest {

    private lateinit var database: AppDatabase
    private val dao: SyncDao get() = database.syncDao()
    private val todoDao: TodoDao get() = database.todoDao()

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

    private fun entity(
        id: String,
        title: String = "title of $id",
        updatedAt: Long = 100,
        deletedAt: Long? = null,
        syncStatus: SyncStatus = SyncStatus.PENDING,
    ) = TodoEntity(
        id = id,
        title = title,
        description = "",
        isDone = false,
        createdAt = 1,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        syncStatus = syncStatus,
    )

    // ---- getPending / observePendingCount / getAnyById ----------------------------------------

    @Test
    fun pendingRowsIncludeTombstonesAndExcludeSyncedRows() = runTest {
        todoDao.insert(entity("live", syncStatus = SyncStatus.PENDING))
        todoDao.insert(entity("dead", deletedAt = 50, syncStatus = SyncStatus.PENDING))
        todoDao.insert(entity("done", syncStatus = SyncStatus.SYNCED))

        assertEquals(setOf("live", "dead"), dao.getPending().map { it.id }.toSet())
    }

    @Test
    fun pendingRowsComeOldestChangeFirst() = runTest {
        todoDao.insert(entity("c", updatedAt = 300))
        todoDao.insert(entity("a", updatedAt = 100))
        todoDao.insert(entity("b", updatedAt = 200))
        // Same updatedAt: the id makes the order deterministic.
        todoDao.insert(entity("b2", updatedAt = 200))

        assertEquals(listOf("a", "b", "b2", "c"), dao.getPending().map { it.id })
    }

    @Test
    fun pendingCountCountsTombstonesAndReactsToChanges() = runTest {
        assertEquals(0, dao.observePendingCount().first())

        todoDao.insert(entity("live"))
        todoDao.insert(entity("dead", deletedAt = 50))
        todoDao.insert(entity("done", syncStatus = SyncStatus.SYNCED))
        assertEquals(2, dao.observePendingCount().first())

        dao.markSynced("live", expectedUpdatedAt = 100)
        assertEquals(1, dao.observePendingCount().first())
    }

    @Test
    fun pendingCountFlowEmitsWhenARowBecomesPending() = runTest {
        todoDao.insert(entity("a", syncStatus = SyncStatus.SYNCED))
        // Subscribed first, then the write: this is how the scheduler uses it.
        val seen = MutableStateFlow(emptyList<Int>())
        backgroundScope.launch { dao.observePendingCount().collect { value -> seen.update { it + value } } }
        seen.first { it.isNotEmpty() }

        todoDao.setDone("a", isDone = true, now = 200)

        seen.first { it.lastOrNull() == 1 }
        assertEquals(0, seen.value.first())
    }

    @Test
    fun getAnyByIdReturnsTombstonesWhileGetByIdHidesThem() = runTest {
        todoDao.insert(entity("dead", deletedAt = 50))

        assertNull(todoDao.getById("dead"))
        assertEquals(50L, dao.getAnyById("dead")?.deletedAt)
        assertNull(dao.getAnyById("unknown"))
    }

    // ---- markSynced: the race guard -----------------------------------------------------------

    @Test
    fun markSyncedUpdatesARowThatStillHasTheSentUpdatedAt() = runTest {
        todoDao.insert(entity("a", updatedAt = 100))

        val updated = dao.markSynced("a", expectedUpdatedAt = 100)

        assertEquals(1, updated)
        assertEquals(SyncStatus.SYNCED, dao.getAnyById("a")?.syncStatus)
    }

    @Test
    fun markSyncedDoesNothingWhenTheRowWasEditedMeanwhile() = runTest {
        todoDao.insert(entity("a", updatedAt = 100))
        // The user edits while the push (which carried updatedAt = 100) is in flight.
        todoDao.updateContent("a", title = "edited", description = "", now = 150)

        val updated = dao.markSynced("a", expectedUpdatedAt = 100)

        assertEquals(0, updated)
        val row = dao.getAnyById("a")
        assertEquals(SyncStatus.PENDING, row?.syncStatus)
        assertEquals(150L, row?.updatedAt)
        assertEquals("edited", row?.title)
    }

    @Test
    fun markSyncedCanMarkATombstone() = runTest {
        todoDao.insert(entity("dead", updatedAt = 100, deletedAt = 100))

        assertEquals(1, dao.markSynced("dead", expectedUpdatedAt = 100))

        assertEquals(SyncStatus.SYNCED, dao.getAnyById("dead")?.syncStatus)
        assertEquals(100L, dao.getAnyById("dead")?.deletedAt)
    }

    // ---- upsertRemote -------------------------------------------------------------------------

    @Test
    fun upsertRemoteInsertsAndReplacesAndKeepsTheGivenStatus() = runTest {
        dao.upsertRemote(entity("a", title = "first", updatedAt = 100, syncStatus = SyncStatus.SYNCED))
        assertEquals("first", dao.getAnyById("a")?.title)

        dao.upsertRemote(entity("a", title = "second", updatedAt = 200, syncStatus = SyncStatus.SYNCED))

        val row = dao.getAnyById("a")
        assertEquals("second", row?.title)
        assertEquals(200L, row?.updatedAt)
        assertEquals(SyncStatus.SYNCED, row?.syncStatus)
        assertEquals(1, database.todoDao().observeAll().first().size)
    }

    @Test
    fun upsertRemoteCanWriteATombstoneOverALiveRowAndResurrectItAgain() = runTest {
        todoDao.insert(entity("a", updatedAt = 100, syncStatus = SyncStatus.SYNCED))

        dao.upsertRemote(entity("a", updatedAt = 200, deletedAt = 200, syncStatus = SyncStatus.SYNCED))
        assertEquals(emptyList(), todoDao.observeAll().first())
        assertEquals(200L, dao.getAnyById("a")?.deletedAt)

        // A user write cannot touch a tombstone, but a newer remote version may.
        todoDao.updateContent("a", title = "ignored", description = "", now = 250)
        assertEquals(200L, dao.getAnyById("a")?.deletedAt)

        dao.upsertRemote(entity("a", title = "back", updatedAt = 300, deletedAt = null, syncStatus = SyncStatus.SYNCED))
        assertEquals(listOf("back"), todoDao.observeAll().first().map { it.title })
    }

    // ---- markAllPending -----------------------------------------------------------------------

    @Test
    fun markAllPendingMarksEveryRowIncludingTombstonesAndKeepsUpdatedAt() = runTest {
        todoDao.insert(entity("live", updatedAt = 111, syncStatus = SyncStatus.SYNCED))
        todoDao.insert(entity("dead", updatedAt = 222, deletedAt = 222, syncStatus = SyncStatus.SYNCED))
        todoDao.insert(entity("already", updatedAt = 333, syncStatus = SyncStatus.PENDING))

        dao.markAllPending()

        val rows = dao.getPending().associateBy { it.id }
        assertEquals(setOf("live", "dead", "already"), rows.keys)
        // updatedAt is the version; bumping it would let the re-pushed rows beat newer remote edits.
        assertEquals(111L, rows.getValue("live").updatedAt)
        assertEquals(222L, rows.getValue("dead").updatedAt)
        assertEquals(333L, rows.getValue("already").updatedAt)
    }

    // ---- cursor -------------------------------------------------------------------------------

    @Test
    fun cursorIsNullUntilSavedAndThenReplacedInPlace() = runTest {
        assertNull(dao.getCursor())

        dao.saveCursor(SyncCursorEntity(serverId = "server-1", revision = 5))
        assertEquals(SyncCursorEntity(serverId = "server-1", revision = 5), dao.getCursor())

        dao.saveCursor(SyncCursorEntity(serverId = "server-2", revision = 0))
        assertEquals(SyncCursorEntity(serverId = "server-2", revision = 0), dao.getCursor())
    }

    @Test
    fun cursorCanHoldANullServerId() = runTest {
        dao.saveCursor(SyncCursorEntity(serverId = null, revision = 0))

        assertEquals(SyncCursorEntity(serverId = null, revision = 0), dao.getCursor())
    }
}
