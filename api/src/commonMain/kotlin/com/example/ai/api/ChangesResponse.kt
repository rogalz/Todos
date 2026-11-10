package com.example.ai.api

import kotlinx.serialization.Serializable

/**
 * Answer to `GET /todos`.
 *
 * @property items todos changed since the client's cursor, tombstones included.
 * @property revision cursor for the next pull. It is a server counter, not a time, so it cannot
 *   skip or repeat changes however the device clocks differ.
 * @property serverId UUID of this server instance, new on every server start. The client stores it
 *   next to [revision] and sends both on the next pull.
 * @property reset `true` when the client's `serverId` is not the current one, i.e. the server was
 *   restarted and lost its (in-memory) todos. Then [items] is a full snapshot and the client's
 *   cursor is meaningless.
 *
 * **Client rule for `reset = true`: do NOT drop local rows.** The server lost data, the clients did
 * not. Mark all local rows `PENDING` and push them again (last-write-wins makes that safe), then
 * store the new [serverId] and [revision]. That is how the clients rebuild the restarted server;
 * replacing the local view with [items] would delete every already-synced todo that only the
 * clients still had.
 */
@Serializable
data class ChangesResponse(
    val items: List<TodoDto>,
    val revision: Long,
    val serverId: String,
    val reset: Boolean,
)
