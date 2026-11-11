package com.example.ai.api

/** Paths and parameter names, shared so client and server cannot drift apart. */
object ApiRoutes {
    const val HEALTH = "/health"
    const val TODOS = "/todos"

    /** `GET`, `text/event-stream`: see [ServerEvent]. */
    const val EVENTS = "/events"

    /** SSE `event:` names of [ServerEvent.Hello] and [ServerEvent.Changed]. */
    const val EVENT_HELLO = "hello"
    const val EVENT_CHANGED = "changed"

    const val PARAM_ID = "id"
    const val PARAM_SINCE = "since"
    const val PARAM_SERVER_ID = "serverId"

    /** Route template for the server (`PUT`); clients use [todoPath]. */
    const val TODO_BY_ID = "$TODOS/{$PARAM_ID}"

    fun todoPath(id: String): String = "$TODOS/$id"
}
