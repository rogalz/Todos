package com.example.ai.data.remote

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Tolerant reader: a newer server may add fields to the DTOs, and an older client must keep
 * working, so unknown keys are ignored. Everything else stays strict (a missing required field
 * still fails).
 */
internal val TodoJson = Json { ignoreUnknownKeys = true }

/**
 * The one place that configures the client, used by the app and by the tests (which pass the
 * client of Ktor's `testApplication`), so tests run with the real configuration.
 *
 * The timeouts matter beyond being polite: the sync runs under a single-flight lock, so a server
 * that accepts the connection and then never answers would otherwise block every later sync and
 * leave the state at `Syncing` forever. Connect is short (a dead host should fail fast), the whole
 * request has more room because a pull may be large.
 */
fun HttpClientConfig<*>.configureTodoClient(
    connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) {
    install(ContentNegotiation) { json(TodoJson) }
    install(HttpTimeout) {
        this.connectTimeoutMillis = connectTimeoutMillis
        this.requestTimeoutMillis = requestTimeoutMillis
    }
}

const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000L
const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000L
