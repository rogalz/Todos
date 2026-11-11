package com.example.ai.data.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.ai.api.ChangesResponse
import com.example.ai.api.TodoDto
import com.example.ai.data.local.AppDatabase
import com.example.ai.data.local.SyncCursorEntity
import com.example.ai.data.local.SyncDao
import com.example.ai.data.local.SyncStatus
import com.example.ai.data.local.TodoEntity
import com.example.ai.data.toEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The rules of how a server version meets a local row, on a real in-memory SQLite. */
class SyncStoreTest {

    private lateinit var database: AppDatabase
    private lateinit var store: SyncStore
    private val dao: SyncDao get() = database.syncDao()

    @BeforeTest
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder<AppDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        store = SyncStore(database, database.syncDao())
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    private fun dto(
        id: String = "a",
        title: String = "title",
        updatedAt: Long = 100,
        deletedAt: Long? = null,
        isDone: Boolean = false,
    ) = TodoDto(id, title, description = "", isDone = isDone, createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt)

    private suspend fun givenLocal(todo: TodoDto, status: SyncStatus) = dao.upsertRemote(todo.toEntity(status))

    private suspend fun row(id: String = "a"): TodoEntity? = dao.getAnyById(id)

    private fun changes(
        vararg items: TodoDto,
        revision: Long = 7,
        serverId: String = "server-1",
        reset: Boolean = false,
    ) = ChangesResponse(items.toList(), revision, serverId, reset)

    // ---- cursor -------------------------------------------------------------------------------

    @Test
    fun cursorIsNeverSyncedUntilAPullWasApplied() = runTest {
        assertEquals(SyncCursor(serverId = null, revision = 0), store.readCursor())

        store.applyPull(changes(revision = 7, serverId = "server-1"))

        assertEquals(SyncCursor(serverId = "server-1", revision = 7), store.readCursor())
    }

    @Test
    fun pendingTodosAreOldestFirstAndIncludeTombstones() = runTest {
        givenLocal(dto("b", updatedAt = 200), SyncStatus.PENDING)
        givenLocal(dto("a", updatedAt = 100, deletedAt = 100), SyncStatus.PENDING)
        givenLocal(dto("done", updatedAt = 50), SyncStatus.SYNCED)

        assertEquals(listOf("a", "b"), store.pendingTodos().map { it.id })
        assertEquals(100L, store.pendingTodos().first().deletedAt)
    }

    // ---- pull rule ----------------------------------------------------------------------------

    @Test
    fun pullInsertsAnUnknownTodoAsSynced() = runTest {
        store.applyPull(changes(dto("a", title = "from server")))

        assertEquals(dto("a", title = "from server").toEntity(SyncStatus.SYNCED), row())
    }

    @Test
    fun pullOverwritesAnOlderSyncedRow() = runTest {
        givenLocal(dto(updatedAt = 100, title = "old"), SyncStatus.SYNCED)

        store.applyPull(changes(dto(updatedAt = 200, title = "new")))

        assertEquals("new", row()?.title)
        assertEquals(200L, row()?.updatedAt)
        assertEquals(SyncStatus.SYNCED, row()?.syncStatus)
    }

    @Test
    fun pullIgnoresAnOlderRemoteVersion() = runTest {
        givenLocal(dto(updatedAt = 200, title = "local"), SyncStatus.SYNCED)

        store.applyPull(changes(dto(updatedAt = 100, title = "stale remote")))

        assertEquals(dto(updatedAt = 200, title = "local").toEntity(SyncStatus.SYNCED), row())
    }

    @Test
    fun pullKeepsTheLocalRowOnATie() = runTest {
        givenLocal(dto(updatedAt = 100, title = "local"), SyncStatus.SYNCED)

        store.applyPull(changes(dto(updatedAt = 100, title = "remote with the same updatedAt")))

        assertEquals("local", row()?.title)
    }

    @Test
    fun pullNeverOverwritesANewerLocalPendingRow() = runTest {
        givenLocal(dto(updatedAt = 300, title = "my unsynced edit"), SyncStatus.PENDING)

        store.applyPull(changes(dto(updatedAt = 200, title = "older remote")))

        assertEquals("my unsynced edit", row()?.title)
        assertEquals(SyncStatus.PENDING, row()?.syncStatus)
    }

    @Test
    fun pullOfANewerRemoteVersionBeatsAnOlderLocalPendingRow() = runTest {
        // Last-write-wins: the local edit was older, so it lost; the row is in sync with the server again.
        givenLocal(dto(updatedAt = 100, title = "my older edit"), SyncStatus.PENDING)

        store.applyPull(changes(dto(updatedAt = 200, title = "newer remote")))

        assertEquals("newer remote", row()?.title)
        assertEquals(SyncStatus.SYNCED, row()?.syncStatus)
    }

    @Test
    fun pulledTombstoneHidesTheTodoFromTheUi() = runTest {
        givenLocal(dto(updatedAt = 100), SyncStatus.SYNCED)
        assertEquals(listOf("a"), database.todoDao().observeAll().first().map { it.id })

        store.applyPull(changes(dto(updatedAt = 200, deletedAt = 200)))

        assertEquals(emptyList(), database.todoDao().observeAll().first())
        assertEquals(200L, row()?.deletedAt)
        assertEquals(SyncStatus.SYNCED, row()?.syncStatus)
    }

    @Test
    fun pulledNewerLiveVersionResurrectsALocalTombstone() = runTest {
        givenLocal(dto(updatedAt = 100, deletedAt = 100), SyncStatus.SYNCED)

        store.applyPull(changes(dto(updatedAt = 200, title = "back", deletedAt = null)))

        assertEquals(listOf("back"), database.todoDao().observeAll().first().map { it.title })
    }

    @Test
    fun pullOfALocalTombstoneThatIsNewerStaysDeleted() = runTest {
        givenLocal(dto(updatedAt = 300, deletedAt = 300), SyncStatus.PENDING)

        store.applyPull(changes(dto(updatedAt = 200, title = "older live version")))

        assertEquals(300L, row()?.deletedAt)
        assertEquals(SyncStatus.PENDING, row()?.syncStatus)
    }

    @Test
    fun pullStoresItemsAndCursorTogether() = runTest {
        store.applyPull(changes(dto("a"), dto("b"), revision = 12, serverId = "server-9"))

        assertEquals(setOf("a", "b"), setOf(row("a")?.id, row("b")?.id))
        assertEquals(SyncCursor("server-9", 12), store.readCursor())
    }

    // ---- reset --------------------------------------------------------------------------------

    @Test
    fun resetMarksLocalOnlyRowsPendingWithoutTouchingUpdatedAtAndKeepsSnapshotRowsSynced() = runTest {
        givenLocal(dto("local-only", updatedAt = 111), SyncStatus.SYNCED)
        givenLocal(dto("tombstone", updatedAt = 222, deletedAt = 222), SyncStatus.SYNCED)
        givenLocal(dto("known", updatedAt = 100, title = "old"), SyncStatus.SYNCED)
        dao.saveCursor(SyncCursorEntity(serverId = "old-server", revision = 40))

        store.applyPull(
            changes(dto("known", updatedAt = 200, title = "from the new server"), revision = 3, serverId = "new-server", reset = true),
        )

        // Came back from the snapshot, so nothing to upload.
        assertEquals(SyncStatus.SYNCED, row("known")?.syncStatus)
        assertEquals("from the new server", row("known")?.title)
        // Only the clients still have these: they must rebuild the server, tombstones included.
        assertEquals(SyncStatus.PENDING, row("local-only")?.syncStatus)
        assertEquals(SyncStatus.PENDING, row("tombstone")?.syncStatus)
        // updatedAt is the version; it must not change or the re-push would beat newer remote edits.
        assertEquals(111L, row("local-only")?.updatedAt)
        assertEquals(222L, row("tombstone")?.updatedAt)
        assertEquals(SyncCursor("new-server", 3), store.readCursor())
    }

    @Test
    fun resetKeepsTheLocalRowThatIsNewerThanTheSnapshotAndUploadsIt() = runTest {
        givenLocal(dto("a", updatedAt = 300, title = "newer here"), SyncStatus.SYNCED)

        store.applyPull(changes(dto("a", updatedAt = 200, title = "older on the server"), reset = true))

        assertEquals("newer here", row("a")?.title)
        assertEquals(SyncStatus.PENDING, row("a")?.syncStatus)
    }

    @Test
    fun resetDoesNotDropAnyLocalRow() = runTest {
        givenLocal(dto("a"), SyncStatus.SYNCED)
        givenLocal(dto("b"), SyncStatus.SYNCED)

        store.applyPull(changes(reset = true))

        assertEquals(setOf("a", "b"), dao.getPending().map { it.id }.toSet())
    }

    // ---- atomicity ----------------------------------------------------------------------------

    /** A DAO that fails on purpose, to see what the store leaves behind. */
    private class FailingSyncDao(
        private val delegate: SyncDao,
        private val failOnUpsertNumber: Int? = null,
        private val failOnSaveCursor: Boolean = false,
    ) : SyncDao by delegate {
        private var upserts = 0

        override suspend fun upsertRemote(entity: TodoEntity) {
            upserts += 1
            if (upserts == failOnUpsertNumber) error("boom while applying item $upserts")
            delegate.upsertRemote(entity)
        }

        override suspend fun saveCursor(cursor: SyncCursorEntity) {
            if (failOnSaveCursor) error("boom while saving the cursor")
            delegate.saveCursor(cursor)
        }
    }

    @Test
    fun failureWhileApplyingAnItemRollsBackTheEarlierItemsAndLeavesTheCursor() = runTest {
        dao.saveCursor(SyncCursorEntity(serverId = "server-1", revision = 5))
        val failing = SyncStore(database, FailingSyncDao(database.syncDao(), failOnUpsertNumber = 2))

        assertFailsWith<IllegalStateException> {
            failing.applyPull(changes(dto("first"), dto("second"), revision = 9, serverId = "server-1"))
        }

        assertNull(row("first"), "the first item was written inside the same transaction and must be rolled back")
        assertEquals(SyncCursor("server-1", 5), store.readCursor())
    }

    @Test
    fun failureWhileSavingTheCursorRollsBackTheItems() = runTest {
        val failing = SyncStore(database, FailingSyncDao(database.syncDao(), failOnSaveCursor = true))

        assertFailsWith<IllegalStateException> { failing.applyPull(changes(dto("a"), dto("b"))) }

        assertNull(row("a"))
        assertNull(row("b"))
        assertEquals(SyncCursor(serverId = null, revision = 0), store.readCursor())
    }

    @Test
    fun failureDuringResetRollsBackMarkAllPending() = runTest {
        givenLocal(dto("a"), SyncStatus.SYNCED)
        val failing = SyncStore(database, FailingSyncDao(database.syncDao(), failOnSaveCursor = true))

        assertFailsWith<IllegalStateException> { failing.applyPull(changes(reset = true)) }

        assertEquals(SyncStatus.SYNCED, row("a")?.syncStatus)
    }

    // ---- push response rule -------------------------------------------------------------------

    @Test
    fun pushResponseThatEchoesWhatWeSentMarksTheRowSynced() = runTest {
        val sent = dto(updatedAt = 100, title = "mine")
        givenLocal(sent, SyncStatus.PENDING)

        store.applyPushResponse(sent, response = sent)

        assertEquals(sent.toEntity(SyncStatus.SYNCED), row())
    }

    @Test
    fun pushedTombstoneThatTheServerAcceptedStaysATombstoneAndIsSynced() = runTest {
        val sent = dto(updatedAt = 100, deletedAt = 100)
        givenLocal(sent, SyncStatus.PENDING)

        store.applyPushResponse(sent, response = sent)

        assertEquals(SyncStatus.SYNCED, row()?.syncStatus)
        assertEquals(100L, row()?.deletedAt)
    }

    @Test
    fun pushResponseWithANewerServerVersionOverwritesTheLocalRow() = runTest {
        val sent = dto(updatedAt = 100, title = "mine")
        givenLocal(sent, SyncStatus.PENDING)
        val serverVersion = dto(updatedAt = 200, title = "edited on another device", isDone = true)

        store.applyPushResponse(sent, response = serverVersion)

        assertEquals(serverVersion.toEntity(SyncStatus.SYNCED), row())
    }

    @Test
    fun pushResponseTieWithDifferentContentAdoptsTheServersVersion() = runTest {
        // Another device wrote at the very same millisecond and the server kept its version. If we
        // kept ours and marked it SYNCED, this client and the server would differ forever.
        val sent = dto(updatedAt = 100, title = "mine")
        givenLocal(sent, SyncStatus.PENDING)
        val serverVersion = dto(updatedAt = 100, title = "the server's version")

        store.applyPushResponse(sent, response = serverVersion)

        assertEquals(serverVersion.toEntity(SyncStatus.SYNCED), row())
    }

    @Test
    fun theSameTieInAPullKeepsTheLocalRow() = runTest {
        // Contrast to the previous test: the push response is the only place where the server arbitrates.
        givenLocal(dto(updatedAt = 100, title = "mine"), SyncStatus.PENDING)

        store.applyPull(changes(dto(updatedAt = 100, title = "the server's version")))

        assertEquals("mine", row()?.title)
        assertEquals(SyncStatus.PENDING, row()?.syncStatus)
    }

    @Test
    fun editMadeDuringThePushIsKeptPendingWhenTheResponseIsOlder() = runTest {
        val sent = dto(updatedAt = 100, title = "pushed")
        givenLocal(sent, SyncStatus.PENDING)
        // The user edits while the PUT is in flight.
        database.todoDao().updateContent("a", title = "edited during push", description = "", now = 150)

        store.applyPushResponse(sent, response = sent)

        assertEquals("edited during push", row()?.title)
        assertEquals(150L, row()?.updatedAt)
        assertEquals(SyncStatus.PENDING, row()?.syncStatus)
    }

    @Test
    fun serverVersionNewerThanAnEditMadeDuringThePushStillWins() = runTest {
        val sent = dto(updatedAt = 100, title = "pushed")
        givenLocal(sent, SyncStatus.PENDING)
        database.todoDao().updateContent("a", title = "edited during push", description = "", now = 150)
        val serverVersion = dto(updatedAt = 200, title = "newer on the server")

        store.applyPushResponse(sent, response = serverVersion)

        // Last-write-wins on updatedAt, same as everywhere else.
        assertEquals("newer on the server", row()?.title)
        assertEquals(SyncStatus.SYNCED, row()?.syncStatus)
    }

    @Test
    fun editMadeDuringThePushIsKeptEvenWhenTheServerSentATombstoneThatIsOlder() = runTest {
        val sent = dto(updatedAt = 100)
        givenLocal(sent, SyncStatus.PENDING)
        database.todoDao().setDone("a", isDone = true, now = 150)

        store.applyPushResponse(sent, response = dto(updatedAt = 120, deletedAt = 120))

        assertEquals(null, row()?.deletedAt)
        assertEquals(true, row()?.isDone)
        assertEquals(SyncStatus.PENDING, row()?.syncStatus)
    }

    @Test
    fun pushResponseForAnIdWeDidNotSendIsRejectedAndChangesNothing() = runTest {
        val sent = dto("a", updatedAt = 100)
        givenLocal(sent, SyncStatus.PENDING)

        assertFailsWith<IllegalStateException> { store.applyPushResponse(sent, response = dto("other", updatedAt = 100)) }

        assertEquals(SyncStatus.PENDING, row("a")?.syncStatus)
        assertNull(row("other"))
    }
}
