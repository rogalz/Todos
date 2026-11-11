package com.example.ai.server

import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

private val log = LoggerFactory.getLogger("LlmWarmUp")

/**
 * One tiny request at startup, so the model is already loaded when the first user asks (a cold
 * model takes seconds). It is only a courtesy: if Ollama is not up yet, that is logged and the
 * server starts anyway.
 */
internal suspend fun LlmClient.warmUp() {
    try {
        parse("warm up")
        log.info("LLM warmed up")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("LLM warm-up failed (the first /parse will be slow or fail): {}", e.message)
    }
}
