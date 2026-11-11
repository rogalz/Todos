package com.example.ai.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The SSE heartbeat. Clients treat three missed ones (45 s) as a dead connection. */
val DEFAULT_HEARTBEAT_PERIOD: Duration = 15.seconds

/**
 * The whole server as an [Application] extension, so tests can start it with a store they control
 * (`testApplication { application { module(store) } }`) and `main` can start it with a fresh one.
 *
 * @param heartbeatPeriod a parameter so tests do not have to wait 15 s to see a heartbeat.
 * @param llm behind `POST /parse`; tests pass a fake. The default answers 503 (see [NoLlmClient]).
 */
fun Application.module(
    store: TodoStore = TodoStore(),
    heartbeatPeriod: Duration = DEFAULT_HEARTBEAT_PERIOD,
    llm: LlmClient = NoLlmClient,
) {
    // One log line per request: status, method, path and duration.
    install(CallLogging)
    install(ContentNegotiation) {
        // Tolerant reader: an older server must keep accepting a newer client that sends extra
        // fields, so unknown keys are ignored. Everything else stays strict (malformed JSON and
        // missing required fields are still 400); Ktor's own default is lenient, which we do not want.
        json(Json { ignoreUnknownKeys = true })
    }
    install(SSE)
    routing {
        todoRoutes(store)
        eventRoutes(store, heartbeatPeriod)
        parseRoutes(llm)
    }
}
