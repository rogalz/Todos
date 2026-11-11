package com.example.ai.data.remote

import com.example.ai.api.ChangesResponse
import com.example.ai.api.ParsedTodoDto
import com.example.ai.api.TodoDto

/**
 * The server, as the sync engine (and the draft parser) sees it. Every failure is an exception: [ApiException] when the
 * server answered with an error status, anything else (I/O, timeout, malformed answer) when it
 * did not answer properly. The engine turns both into a failed sync.
 */
interface TodoApi {

    /** Idempotent upsert (create, edit, toggle and delete alike). Returns the server's winning version. */
    suspend fun push(todo: TodoDto): TodoDto

    /** Changes after [since]; [serverId] is the one from the previous pull (`null` on the very first). */
    suspend fun pull(since: Long, serverId: String?): ChangesResponse

    /**
     * Asks the server's local LLM to turn [text] into a todo draft. Slow compared to the other
     * calls (seconds), hence its own, longer timeout. Nothing is stored on the server.
     */
    suspend fun parse(text: String): ParsedTodoDto
}
