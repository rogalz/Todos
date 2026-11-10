package com.example.ai.server

import com.example.ai.api.ApiRoutes
import com.example.ai.api.TodoDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

fun Route.todoRoutes(store: TodoStore) {
    get(ApiRoutes.HEALTH) {
        call.respondText("OK")
    }

    get(ApiRoutes.TODOS) {
        // Validated before anything else: a malformed request stays malformed even when the
        // serverId would make `since` irrelevant.
        val since = when (val checked = parseSince(call.request.queryParameters[ApiRoutes.PARAM_SINCE])) {
            is Checked.Valid -> checked.value
            is Checked.Invalid -> return@get call.respondText(checked.reason, status = HttpStatusCode.BadRequest)
        }
        // Opaque on purpose: any value other than the current id means "not the server you talked to".
        val serverId = call.request.queryParameters[ApiRoutes.PARAM_SERVER_ID]

        call.respond(store.changes(since = since, clientServerId = serverId))
    }

    put(ApiRoutes.TODO_BY_ID) {
        val pathId = call.parameters[ApiRoutes.PARAM_ID].orEmpty()
        val body = call.receive<TodoDto>()
        val todo = when (val checked = validateUpsert(pathId, body)) {
            is Checked.Valid -> checked.value
            is Checked.Invalid -> return@put call.respondText(checked.reason, status = HttpStatusCode.BadRequest)
        }

        // 200 for create and update alike, and also when the write lost: the body is the winning
        // version either way, and the client reconciles from it.
        call.respond(store.upsert(todo))
    }
}
