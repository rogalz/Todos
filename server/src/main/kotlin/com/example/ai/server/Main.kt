package com.example.ai.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val DEFAULT_PORT = 8080

fun main() {
    val port = parsePort(System.getenv("PORT"))
    val llm = OllamaLlmClient.create(OllamaConfig.fromEnv())
    // Fire and forget, off the startup path: the server must come up even if Ollama is not there yet.
    CoroutineScope(Dispatchers.IO).launch { llm.warmUp() }
    try {
        // 0.0.0.0, not localhost: inside Docker the published port only reaches a server that listens
        // on every interface. (The Android emulator reaches the host as 10.0.2.2 either way.)
        embeddedServer(Netty, port = port, host = "0.0.0.0") { module(llm = llm) }.start(wait = true)
    } finally {
        llm.close()
    }
}

/** `PORT` env value → port. Unset or blank means the default; anything else must be a valid port. */
internal fun parsePort(raw: String?): Int {
    if (raw.isNullOrBlank()) return DEFAULT_PORT
    val port = raw.trim().toIntOrNull()
    require(port != null && port in 1..65535) { "PORT must be a number between 1 and 65535, was '$raw'" }
    return port
}
