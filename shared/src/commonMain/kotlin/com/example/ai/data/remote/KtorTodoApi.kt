package com.example.ai.data.remote

import com.example.ai.api.ApiRoutes
import com.example.ai.api.ChangesResponse
import com.example.ai.api.TodoDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * @param client injected, so tests can pass the client of Ktor's `testApplication`. It must be
 *   configured with [configureTodoClient].
 * @param baseUrl e.g. `http://localhost:8080`; a trailing slash is fine.
 */
class KtorTodoApi(
    private val client: HttpClient,
    baseUrl: String,
) : TodoApi {

    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun push(todo: TodoDto): TodoDto =
        client.put("$baseUrl${ApiRoutes.todoPath(todo.id)}") {
            contentType(ContentType.Application.Json)
            setBody(todo)
        }.bodyOrThrow()

    override suspend fun pull(since: Long, serverId: String?): ChangesResponse =
        client.get("$baseUrl${ApiRoutes.TODOS}") {
            parameter(ApiRoutes.PARAM_SINCE, since)
            // Omitted on the very first pull: "no serverId" is how the server recognises it.
            if (serverId != null) parameter(ApiRoutes.PARAM_SERVER_ID, serverId)
        }.bodyOrThrow()

    // Checked here and not with `expectSuccess` on the client: the injected client may come from a
    // test, and the status must always become an ApiException the engine can tell apart.
    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
        if (!status.isSuccess()) throw ApiException(status.value, bodyAsText())
        return body()
    }
}
