package com.example.ai.data.remote

import com.example.ai.api.ChangesResponse
import com.example.ai.api.TodoDto

/**
 * The server, as the sync engine sees it. Every failure is an exception: [ApiException] when the
 * server answered with an error status, anything else (I/O, timeout, malformed answer) when it
 * did not answer properly. The engine turns both into a failed sync.
 */
interface TodoApi {

    /** Idempotent upsert (create, edit, toggle and delete alike). Returns the server's winning version. */
    suspend fun push(todo: TodoDto): TodoDto

    /** Changes after [since]; [serverId] is the one from the previous pull (`null` on the very first). */
    suspend fun pull(since: Long, serverId: String?): ChangesResponse
}
