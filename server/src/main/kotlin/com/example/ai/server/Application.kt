package com.example.ai.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * The whole server as an [Application] extension, so tests can start it with a store they control
 * (`testApplication { application { module(store) } }`) and `main` can start it with a fresh one.
 */
fun Application.module(store: TodoStore = TodoStore()) {
    // One log line per request: status, method, path and duration.
    install(CallLogging)
    install(ContentNegotiation) {
        // Tolerant reader: an older server must keep accepting a newer client that sends extra
        // fields, so unknown keys are ignored. Everything else stays strict (malformed JSON and
        // missing required fields are still 400); Ktor's own default is lenient, which we do not want.
        json(Json { ignoreUnknownKeys = true })
    }
    routing { todoRoutes(store) }
}
