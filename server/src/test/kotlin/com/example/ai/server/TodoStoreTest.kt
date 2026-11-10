package com.example.ai.server

import com.example.ai.api.TodoDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Store-level cases that are awkward or impossible to reach through HTTP: ties, resurrection and
 * real parallelism. The endpoint behaviour from the spec is covered in [TodoRoutesTest].
 *
 * These use `runBlocking` (not `runTest`) on purpose: the concurrency tests need real threads, a
 * virtual-time single-threaded test scheduler would never interleave the writers.
 */
@OptIn(ExperimentalUuidApi::class)
class TodoStoreTest {

    private val store = TodoStore(serverId = "server-1")

    private fun todo(
        id: String = "a",
        updatedAt: Long = 100,
        title: String = "title",
        deletedAt: Long? = null,
    ) = TodoDto(
        id = id, title = title, description = "", isDone = false,
        createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt,
    )

    @Test
    fun equalUpdatedAtIsIgnoredAndFirstWriterWins() = runBlocking {
        // Two devices can edit within the same millisecond. The rule says "strictly newer wins",
        // so the tie keeps what is stored, and the loser gets the winner back to reconcile.
        val first = store.upsert(todo(title = "first", updatedAt = 100))

        val second = store.upsert(todo(title = "second", updatedAt = 100))

        assertEquals(first, second)
        assertEquals("first", second.title)
        assertEquals(1, store.changes(since = 0, clientServerId = null).revision)
    }

    @Test
    fun newerLiveVersionOverridesTombstone() = runBlocking {
        // Delete is just another version: an edit made after the delete (e.g. offline on another
        // device) legitimately brings the todo back under last-write-wins.
        store.upsert(todo(updatedAt = 100, deletedAt = 100))

        val revived = store.upsert(todo(updatedAt = 200, title = "back"))

        assertEquals(null, revived.deletedAt)
        val changes = store.changes(since = 0, clientServerId = null)
        assertEquals(listOf(revived), changes.items)
        assertEquals(2, changes.revision)
    }

    @Test
    fun olderLiveVersionDoesNotReviveTombstone() = runBlocking {
        val tombstone = store.upsert(todo(updatedAt = 200, deletedAt = 200))

        val answer = store.upsert(todo(updatedAt = 100, title = "stale edit"))

        assertEquals(tombstone, answer)
        assertEquals(listOf(tombstone), store.changes(since = 0, clientServerId = null).items)
    }

    @Test
    fun cursorBeyondRevisionWithoutServerIdIsAnEmptyDelta() = runBlocking {
        // Documents the spec rule "serverId omitted: normal response from `since`, reset = false".
        // A client that sends a cursor but no serverId is not something stage 3 produces.
        store.upsert(todo())

        val changes = store.changes(since = 99, clientServerId = null)

        assertEquals(emptyList(), changes.items)
        assertEquals(1, changes.revision)
        assertEquals(false, changes.reset)
    }

    @Test
    fun serverIdIsInjectableAndRandomByDefault() {
        assertEquals("server-1", store.serverId)

        val a = TodoStore().serverId
        val b = TodoStore().serverId
        assertNotEquals(a, b)
        Uuid.parse(a) // throws if it is not a UUID
    }

    @Test
    fun parallelWritesOfDistinctTodosGetGaplessUniqueRevisions() = runBlocking(Dispatchers.Default) {
        val count = 500

        (1..count).map { n -> async { store.upsert(todo(id = "todo-$n", updatedAt = 100)) } }.awaitAll()

        // Every revision 1..count must have been handed out exactly once: pulling from cursor k must
        // return exactly the count - k writes that came after it. A lost update, a duplicate or a gap
        // would break this for some k.
        assertEquals(count.toLong(), store.changes(since = 0, clientServerId = null).revision)
        for (k in 0..count) {
            assertEquals(count - k, store.changes(since = k.toLong(), clientServerId = null).items.size, "since=$k")
        }
    }

    @Test
    fun parallelWritesToOneTodoEndOnTheGreatestUpdatedAt() = runBlocking(Dispatchers.Default) {
        val updates = (1..500L).shuffled()

        updates.map { updatedAt -> async { store.upsert(todo(updatedAt = updatedAt, title = "v$updatedAt")) } }.awaitAll()

        val stored = store.changes(since = 0, clientServerId = null).items.single()
        assertEquals(500, stored.updatedAt)
        assertEquals("v500", stored.title)
    }

    @Test
    fun aPullIsAConsistentSnapshotWhileWritesAreHappening() = runBlocking(Dispatchers.Default) {
        // Distinct todos only, so every applied write adds exactly one item. A snapshot taken under
        // one lock therefore always has as many items as its revision says. If the revision were read
        // outside the lock, a pull could report a cursor behind (or ahead of) the items it returned,
        // and the next pull would skip or repeat changes.
        val writers = launch {
            repeat(1_000) { n -> store.upsert(todo(id = "todo-$n")) }
        }
        val inconsistent = mutableListOf<String>()

        while (writers.isActive) {
            val snapshot = store.changes(since = 0, clientServerId = null)
            if (snapshot.items.size.toLong() != snapshot.revision) {
                inconsistent += "items=${snapshot.items.size} revision=${snapshot.revision}"
            }
        }

        assertTrue(inconsistent.isEmpty(), inconsistent.take(3).toString())
    }
}
