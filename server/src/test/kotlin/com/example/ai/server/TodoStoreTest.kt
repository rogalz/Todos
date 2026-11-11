package com.example.ai.server

import com.example.ai.api.TodoDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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

    private companion object {
        const val EXTRA_EVENT_GRACE_MILLIS = 50L
    }

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

    // ---- change events (stage 4) --------------------------------------------------------------

    /**
     * Collects [TodoStore.events] while [block] runs and returns what arrived. The collector is
     * started undispatched, so it is subscribed before the first write, as the SSE route is.
     *
     * It waits until [expected] events are in and then a short grace period more, so a test that
     * expects fewer events than the store sends fails instead of passing because the extra one was
     * still on its way. Real time on purpose: the collector may run on another thread.
     */
    private suspend fun TodoStore.eventsDuring(expected: Int, block: suspend () -> Unit): List<Long> = coroutineScope {
        val received = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { events.collect { received += it } }
        block()
        withTimeout(10_000) { while (received.size < expected) delay(1) }
        delay(EXTRA_EVENT_GRACE_MILLIS)
        collector.cancelAndJoin()
        received.toList()
    }

    @Test
    fun everyAppliedWriteEmitsItsRevisionInOrder() = runBlocking {
        val received = store.eventsDuring(expected = 3) {
            store.upsert(todo(id = "a", updatedAt = 100))
            store.upsert(todo(id = "b", updatedAt = 100))
            store.upsert(todo(id = "a", updatedAt = 200)) // an update is an applied write too
        }

        assertEquals(listOf(1L, 2L, 3L), received)
    }

    @Test
    fun aTombstoneAndItsResurrectionEmitLikeAnyOtherWrite() = runBlocking {
        val received = store.eventsDuring(expected = 3) {
            store.upsert(todo(updatedAt = 100))
            store.upsert(todo(updatedAt = 200, deletedAt = 200))
            store.upsert(todo(updatedAt = 300))
        }

        assertEquals(listOf(1L, 2L, 3L), received)
    }

    @Test
    fun writesThatChangeNothingEmitNothing() = runBlocking {
        store.upsert(todo(updatedAt = 100, title = "stored"))

        val received = store.eventsDuring(expected = 0) {
            store.upsert(todo(updatedAt = 100, title = "same updatedAt: a tie keeps the stored version"))
            store.upsert(todo(updatedAt = 50, title = "older"))
            store.upsert(todo(updatedAt = 100, title = "stored")) // an exact retry
        }

        assertEquals(emptyList(), received)
        assertEquals(1, store.currentRevision(), "and the revision did not move either")
    }

    @Test
    fun aClientThatConnectsLaterGetsNoReplayOfOldWrites() = runBlocking {
        store.upsert(todo(updatedAt = 100))

        val received = store.eventsDuring(expected = 0) { }

        assertEquals(emptyList(), received, "it learns the current revision from currentRevision(), not from the flow")
        assertEquals(1, store.currentRevision())
    }

    @Test
    fun aSubscriberThatDoesNotKeepUpNeverBlocksWritesAndStillEndsOnTheNewestRevision() = runBlocking {
        val received = mutableListOf<Long>()
        val release = CompletableDeferred<Unit>()
        val newestSeen = CompletableDeferred<Unit>()
        val total = 500L
        val slow = launch(start = CoroutineStart.UNDISPATCHED) {
            store.events.collect {
                received += it
                if (received.size == 1) release.await() // stalls on the very first event, like a stuck connection
                if (it == total) newestSeen.complete(Unit)
            }
        }

        store.upsert(todo(id = "todo-1", updatedAt = 100))
        yield() // the collector takes event 1 and stalls
        withTimeout(10_000) {
            // far more writes than the buffer holds: a writer that had to wait for the subscriber would hang here
            for (n in 2..total) store.upsert(todo(id = "todo-$n", updatedAt = 100))
        }
        release.complete(Unit)
        withTimeout(10_000) { newestSeen.await() }
        slow.cancelAndJoin()

        assertEquals(total, received.last(), "the oldest events were dropped, the newest one is never lost")
        assertEquals(received.sorted(), received, "what is delivered is still in revision order")
        assertEquals(received.distinct(), received)
    }

    @Test
    fun parallelWritesEmitEveryRevisionExactlyOnceAndInOrder() = runBlocking(Dispatchers.Default) {
        val count = 300 // fewer than the buffer, so nothing may be dropped here
        val received = store.eventsDuring(expected = count) {
            (1..count).map { n -> async { store.upsert(todo(id = "todo-$n", updatedAt = 100)) } }.awaitAll()
        }

        // Emitting inside the lock is what makes this hold: outside it two writers could swap.
        assertEquals((1..count).map { it.toLong() }, received)
    }
}
