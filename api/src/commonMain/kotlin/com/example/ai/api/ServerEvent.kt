package com.example.ai.api

import kotlinx.serialization.Serializable

/**
 * What the server tells a connected client over `GET /events` (Server-Sent Events).
 *
 * Events carry **no todo data**, only "something changed": data still arrives through pull, so there
 * is one write path into the client's database and a missed event loses nothing (the next pull
 * catches up). Which event is which is decided by the SSE `event:` field, see [ApiRoutes].
 */
sealed interface ServerEvent {

    /**
     * First event of every connection, `event: hello`, `data:` is this class as JSON. The client
     * treats it as "we may have missed changes while we were disconnected".
     *
     * [serverId] is the same id as in `ChangesResponse`. [revision] is the server's revision at the
     * moment the connection was subscribed.
     */
    @Serializable
    data class Hello(val serverId: String, val revision: Long) : ServerEvent

    /**
     * `event: changed`, `data:` is the bare revision number (`"42"`), sent after every write the
     * server **applied** (a write that lost to a newer version, or changed nothing, sends nothing).
     *
     * There is deliberately no `serverId` here: the id of the connection is the one from its
     * [Hello], which is always the first event.
     */
    data class Changed(val revision: Long) : ServerEvent
}
