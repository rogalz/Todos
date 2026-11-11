package com.example.ai.server

import com.example.ai.api.ParsedTodoDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OllamaConfigTest {

    private fun config(vararg env: Pair<String, String>) = OllamaConfig.fromEnv(env.toMap()::get)

    @Test
    fun unsetOrBlankMeansTheDefaults() {
        for (env in listOf(emptyArray(), arrayOf("OLLAMA_URL" to "", "OLLAMA_MODEL" to "  "))) {
            val config = config(*env)

            assertEquals("http://localhost:11434", config.baseUrl)
            assertEquals("llama3.2:3b", config.model)
        }
    }

    @Test
    fun theEnvironmentOverridesBoth() {
        val config = config("OLLAMA_URL" to "http://host.docker.internal:11434", "OLLAMA_MODEL" to "qwen2.5:7b")

        assertEquals("http://host.docker.internal:11434", config.baseUrl)
        assertEquals("qwen2.5:7b", config.model)
    }

    @Test
    fun aTrailingSlashAndSurroundingSpacesAreDropped() {
        val config = config("OLLAMA_URL" to "  http://ollama:11434/ ", "OLLAMA_MODEL" to " m ")

        assertEquals("http://ollama:11434", config.baseUrl)
        assertEquals("m", config.model)
    }
}

class LlmWarmUpTest {

    private class CountingLlm(val onParse: () -> ParsedTodoDto) : LlmClient {
        var calls = 0

        override suspend fun parse(text: String): ParsedTodoDto {
            calls++
            return onParse()
        }
    }

    @Test
    fun itAsksTheModelOnce() = runBlocking {
        val llm = CountingLlm { ParsedTodoDto("t", "") }

        llm.warmUp()

        assertEquals(1, llm.calls)
    }

    @Test
    fun aFailureIsOnlyLoggedSoTheServerStillStarts() = runBlocking {
        for (failure in listOf<Exception>(LlmUnavailableException("down"), LlmBadOutputException("garbage"), IllegalStateException())) {
            val llm = CountingLlm { throw failure }

            llm.warmUp() // must not throw

            assertEquals(1, llm.calls)
        }
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val llm = CountingLlm { throw CancellationException("shutting down") }

        assertFailsWith<CancellationException> { runBlocking { llm.warmUp() } }
    }
}
