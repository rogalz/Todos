package com.example.ai.testing

import com.example.ai.api.ChangesResponse
import com.example.ai.api.ParsedTodoDto
import com.example.ai.api.TodoDto
import com.example.ai.data.remote.TodoApi

/**
 * A tiny in-memory server for engine tests: the same last-write-wins rule, revision counter and
 * `serverId` / `reset` behaviour as the real one (a tie keeps the stored version), with hooks to
 * suspend or fail a call at the moment a test needs it.
 *
 * It only has to be right enough for the engine tests; the end-to-end tests run the real server,
 * so a drift between the two shows up there.
 */
class FakeTodoApi(var serverId: String = "server-1") : TodoApi {

    private class Stored(val dto: TodoDto, val revision: Long)

    private val todos = LinkedHashMap<String, Stored>()
    private var revision = 0L

    /** Every push that reached the server, in order (a push that [onPush] failed is not here). */
    val pushes = mutableListOf<TodoDto>()

    /** Every pull as `(since, serverId)`, in order. */
    val pulls = mutableListOf<Pair<Long, String?>>()

    /**
     * Runs at the start of every push, before the server looks at it: suspend here to hold a push
     * "in flight", or throw to fail it.
     */
    var onPush: suspend (TodoDto) -> Unit = {}

    /** Same for pulls. */
    var onPull: suspend () -> Unit = {}

    /** Every text sent to `parse`, in order. */
    val parses = mutableListOf<String>()

    /** What `parse` answers; replace it to suspend or throw. */
    var onParse: suspend (String) -> ParsedTodoDto = { ParsedTodoDto(title = it, description = "") }

    val stored: Map<String, TodoDto> get() = todos.mapValues { it.value.dto }

    /** A version that another device pushed earlier. */
    fun seed(todo: TodoDto) {
        store(todo)
    }

    /** The server process restarted: all data and the revision counter are gone, the id is new. */
    fun restart(newServerId: String) {
        todos.clear()
        revision = 0
        serverId = newServerId
    }

    override suspend fun push(todo: TodoDto): TodoDto {
        onPush(todo)
        pushes += todo
        return store(todo)
    }

    override suspend fun pull(since: Long, serverId: String?): ChangesResponse {
        onPull()
        pulls += since to serverId
        val restarted = serverId != null && serverId != this.serverId
        val items = todos.values.filter { restarted || it.revision > since }.sortedBy { it.revision }.map { it.dto }
        return ChangesResponse(items = items, revision = revision, serverId = this.serverId, reset = restarted)
    }

    override suspend fun parse(text: String): ParsedTodoDto {
        parses += text
        return onParse(text)
    }

    private fun store(incoming: TodoDto): TodoDto {
        val current = todos[incoming.id]
        if (current != null && incoming.updatedAt <= current.dto.updatedAt) return current.dto
        revision += 1
        todos[incoming.id] = Stored(incoming, revision)
        return incoming
    }
}
