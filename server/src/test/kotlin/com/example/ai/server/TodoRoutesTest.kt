package com.example.ai.server

import com.example.ai.api.ApiRoutes
import com.example.ai.api.ChangesResponse
import com.example.ai.api.TodoDto
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The endpoint behaviour from docs/specs/02-backend.md, through `testApplication` (no real network).
 * Bodies are encoded and decoded with a plain [Json] so the tests also check the wire format a
 * client sees, not just a round trip through the server's own configuration.
 */
class TodoRoutesTest {

    private val json = Json

    private fun todo(
        id: String = ID_A,
        title: String = "title",
        updatedAt: Long = 100,
        deletedAt: Long? = null,
    ) = TodoDto(
        id = id, title = title, description = "", isDone = false,
        createdAt = 1, updatedAt = updatedAt, deletedAt = deletedAt,
    )

    /** Runs [block] against the real module wired to a store with a known `serverId`. */
    private fun routesTest(block: suspend HttpClient.() -> Unit) = testApplication {
        application { module(TodoStore(serverId = SERVER_ID)) }
        client.block()
    }

    private suspend fun HttpClient.putTodo(dto: TodoDto, pathId: String = dto.id): HttpResponse =
        putRaw(ApiRoutes.todoPath(pathId), json.encodeToString(dto))

    private suspend fun HttpClient.putRaw(path: String, body: String): HttpResponse =
        put(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.pull(since: Long? = null, serverId: String? = null): ChangesResponse {
        val response = pullRaw(since?.toString(), serverId)
        assertEquals(HttpStatusCode.OK, response.status)
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun HttpClient.pullRaw(since: String?, serverId: String? = null): HttpResponse {
        val query = listOfNotNull(
            since?.let { "${ApiRoutes.PARAM_SINCE}=$it" },
            serverId?.let { "${ApiRoutes.PARAM_SERVER_ID}=$it" },
        ).joinToString("&")
        return get(ApiRoutes.TODOS + if (query.isEmpty()) "" else "?$query")
    }

    private suspend fun HttpResponse.todo(): TodoDto = json.decodeFromString(bodyAsText())

    // ---- the happy paths ----

    @Test
    fun healthIsOk() = routesTest {
        val response = get(ApiRoutes.HEALTH)

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun putNewTodoShowsUpInThePullAndRaisesTheRevision() = routesTest {
        val before = pull(since = 0)
        assertEquals(emptyList(), before.items)
        assertEquals(0, before.revision)

        val response = putTodo(todo())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(todo(), response.todo())
        val after = pull(since = 0)
        assertEquals(listOf(todo()), after.items)
        assertEquals(1, after.revision)
    }

    @Test
    fun theSamePutTwiceIsStoredOnceAndTheRevisionBumpsOnce() = routesTest {
        val first = putTodo(todo())
        val retry = putTodo(todo())

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(first.todo(), retry.todo())
        val changes = pull(since = 0)
        assertEquals(1, changes.items.size)
        assertEquals(1, changes.revision)
    }

    @Test
    fun putWithOlderUpdatedAtIsIgnoredAndAnswersWithTheStoredVersion() = routesTest {
        val newer = todo(title = "newer", updatedAt = 200)
        putTodo(newer)

        val response = putTodo(todo(title = "older", updatedAt = 100))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(newer, response.todo())
        val changes = pull(since = 0)
        assertEquals(listOf(newer), changes.items)
        assertEquals(1, changes.revision)
    }

    @Test
    fun tombstonePutAppearsInTheChangesWithDeletedAt() = routesTest {
        putTodo(todo(updatedAt = 100))
        val tombstone = todo(updatedAt = 200, deletedAt = 200)

        val response = putTodo(tombstone)

        assertEquals(HttpStatusCode.OK, response.status)
        val changes = pull(since = 1)
        assertEquals(listOf(tombstone), changes.items)
        assertEquals(200L, changes.items.single().deletedAt)
        assertEquals(2, changes.revision)
    }

    @Test
    fun aTombstoneMayHaveABlankTitle() = routesTest {
        // Only a live todo needs a title; a delete must not be rejected for what the title became.
        val response = putTodo(todo(title = "", updatedAt = 200, deletedAt = 200))

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun pullSinceReturnsOnlyLaterChanges() = routesTest {
        putTodo(todo(id = ID_A, updatedAt = 100)) // revision 1
        putTodo(todo(id = ID_B, updatedAt = 100)) // revision 2
        val editedA = todo(id = ID_A, title = "edited", updatedAt = 200)
        putTodo(editedA) // revision 3: an edit moves the todo to the end of the line

        assertEquals(listOf(editedA), pull(since = 2).items)
        assertEquals(listOf(todo(id = ID_B, updatedAt = 100), editedA), pull(since = 1).items)
        assertEquals(3, pull(since = 2).revision)
        assertEquals(emptyList(), pull(since = 3).items)
    }

    @Test
    fun pullWithoutSinceMeansEverything() = routesTest {
        putTodo(todo(id = ID_A))
        putTodo(todo(id = ID_B))

        val changes = pull()

        assertEquals(2, changes.items.size)
    }

    // ---- server restart: serverId ----

    @Test
    fun pullWithoutServerIdIsNormalAndCarriesTheCurrentServerId() = routesTest {
        putTodo(todo(id = ID_A))
        putTodo(todo(id = ID_B))

        val changes = pull(since = 1, serverId = null)

        assertEquals(false, changes.reset)
        assertEquals(SERVER_ID, changes.serverId)
        assertEquals(listOf(todo(id = ID_B)), changes.items)
    }

    @Test
    fun pullWithTheCurrentServerIdIsADelta() = routesTest {
        putTodo(todo(id = ID_A))
        putTodo(todo(id = ID_B))

        val changes = pull(since = 1, serverId = SERVER_ID)

        assertEquals(false, changes.reset)
        assertEquals(SERVER_ID, changes.serverId)
        assertEquals(listOf(todo(id = ID_B)), changes.items)
    }

    @Test
    fun pullWithADifferentServerIdIsAFullSnapshotThatIgnoresSince() = routesTest {
        putTodo(todo(id = ID_A))
        putTodo(todo(id = ID_B))
        putTodo(todo(id = ID_B, updatedAt = 200, deletedAt = 200)) // tombstones are part of the snapshot

        // The client's cursor (7) means nothing to this server: it refers to the instance that died.
        val changes = pull(since = 7, serverId = "the-server-before-the-restart")

        assertEquals(true, changes.reset)
        assertEquals(SERVER_ID, changes.serverId)
        assertEquals(3, changes.revision)
        assertEquals(setOf(ID_A, ID_B), changes.items.map { it.id }.toSet())
        assertEquals(200L, changes.items.single { it.id == ID_B }.deletedAt)
    }

    @Test
    fun afterAResetTheNewServerIdGivesDeltasAgain() = routesTest {
        putTodo(todo(id = ID_A))
        val reset = pull(since = 5, serverId = "old")

        val next = pull(since = reset.revision, serverId = reset.serverId)

        assertEquals(false, next.reset)
        assertEquals(emptyList(), next.items)
    }

    // ---- ids ----

    @Test
    fun anUppercaseUuidIsTheSameTodoAsTheLowercaseOne() = routesTest {
        // ID_HEX contains letters; an all-digit id would look the same in both cases and prove nothing.
        val response = putTodo(todo(id = ID_HEX.uppercase()))

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ID_HEX, response.todo().id)
        putTodo(todo(id = ID_HEX)) // same version again: must be a no-op, not a second todo
        val changes = pull(since = 0)
        assertEquals(listOf(ID_HEX), changes.items.map { it.id })
        assertEquals(1, changes.revision)
    }

    // ---- validation: everything here is a 400 and must not touch the store ----

    @Test
    fun putRejectsAnIdThatIsNotAUuid() = routesTest {
        val notUuids = listOf(
            "not-a-uuid",
            "11111111111141118111111111111111", // right digits, no hyphens
            "11111111-1111-4111-8111-11111111111", // one digit short
            "11111111-1111-4111-8111-1111111111111", // one digit too many
            "g1111111-1111-4111-8111-111111111111", // not hex
        )
        for (id in notUuids) {
            val response = putTodo(todo(id = id), pathId = id)

            assertEquals(HttpStatusCode.BadRequest, response.status, "id=$id")
        }
        assertEquals(0, pull().revision)
    }

    @Test
    fun putRejectsAPathIdThatDiffersFromTheBodyId() = routesTest {
        val response = putTodo(todo(id = ID_A), pathId = ID_B)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, pull().revision)
    }

    @Test
    fun putRejectsABodyIdThatIsNotAUuidEvenWhenThePathIsOne() = routesTest {
        val response = putTodo(todo(id = "nope"), pathId = ID_A)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, pull().revision)
    }

    @Test
    fun putRejectsABlankTitleOnALiveTodo() = routesTest {
        for (title in listOf("", "   ", "\t\n")) {
            val response = putTodo(todo(title = title))

            assertEquals(HttpStatusCode.BadRequest, response.status, "title=${title.toCharArray().toList()}")
        }
        assertEquals(0, pull().revision)
    }

    @Test
    fun putRejectsAMalformedBody() = routesTest {
        val validJson = json.encodeToString(todo())
        val bodies = listOf(
            "",
            "not json",
            "{",
            "[]",
            """{"id":"$ID_A"}""", // fields missing
            validJson.replace("\"isDone\":false", "\"isDone\":\"maybe\""), // wrong type
            // Exactly one required field missing: being tolerant of extra fields must not turn a
            // missing one into a default.
            validJson.replace(",\"deletedAt\":null", ""),
        )
        for (body in bodies) {
            val response = putRaw(ApiRoutes.todoPath(ID_A), body)

            assertEquals(HttpStatusCode.BadRequest, response.status, "body=$body")
        }
        assertEquals(0, pull().revision)
    }

    @Test
    fun putIgnoresUnknownFields() = routesTest {
        // Tolerant reader: a newer client may send fields this server version does not know.
        val extras = ""","priority":3,"label":"x","nested":{"a":[1,2]},"flag":null"""
        val body = json.encodeToString(todo()).dropLast(1) + extras + "}"

        val response = putRaw(ApiRoutes.todoPath(ID_A), body)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(todo(), response.todo())
        val changes = pull(since = 0)
        assertEquals(listOf(todo()), changes.items)
        assertEquals(1, changes.revision)
    }

    @Test
    fun putWithoutAJsonContentTypeIsRejected() = routesTest {
        val response = put(ApiRoutes.todoPath(ID_A)) {
            contentType(ContentType.Text.Plain)
            setBody(json.encodeToString(todo()))
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertEquals(0, pull().revision)
    }

    @Test
    fun pullRejectsASinceThatIsNotANonNegativeNumber() = routesTest {
        for (since in listOf("abc", "-1", "", "1.5", "99999999999999999999", "1,2")) {
            val response = pullRaw(since)

            assertEquals(HttpStatusCode.BadRequest, response.status, "since='$since'")
        }
    }

    @Test
    fun pullAcceptsSinceZeroAndTheLargestLong() = routesTest {
        assertEquals(HttpStatusCode.OK, pullRaw("0").status)
        assertEquals(HttpStatusCode.OK, pullRaw(Long.MAX_VALUE.toString()).status)
    }

    @Test
    fun anInvalidSinceIsRejectedEvenWhenTheServerIdWouldMakeItIrrelevant() = routesTest {
        // On a reset `since` is ignored, but a malformed request is still a malformed request.
        val response = pullRaw("abc", serverId = "some-old-server")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    private companion object {
        const val SERVER_ID = "00000000-0000-4000-8000-0000000000aa"
        const val ID_A = "11111111-1111-4111-8111-111111111111"
        const val ID_B = "22222222-2222-4222-8222-222222222222"
        const val ID_HEX = "abcdef01-2345-4678-89ab-cdef01234567"
    }
}
