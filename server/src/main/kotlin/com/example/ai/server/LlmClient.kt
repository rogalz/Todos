package com.example.ai.server

import com.example.ai.api.ParsedTodoDto

/**
 * Turns a sentence into a todo draft. The answer is the model's own output: trimming, the title cap
 * and the blank-title fallback are applied afterwards by [polishDraft], so they behave the same for
 * every implementation.
 *
 * Failures are exceptions the route maps to a status: [LlmUnavailableException] (503) and
 * [LlmBadOutputException] (502).
 */
interface LlmClient {
    suspend fun parse(text: String): ParsedTodoDto
}

/** The model could not be asked or did not answer in time (nothing listening, timeout, an error status). */
class LlmUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The model answered, but not with a JSON object that has a string `title` and a string `description`. */
class LlmBadOutputException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * What `module()` uses when nobody passes an [LlmClient] (the tests of the todo routes): `/parse`
 * then answers 503 instead of the server opening an HTTP client it would never close.
 */
internal object NoLlmClient : LlmClient {
    override suspend fun parse(text: String): ParsedTodoDto = throw LlmUnavailableException("no LLM is configured")
}
