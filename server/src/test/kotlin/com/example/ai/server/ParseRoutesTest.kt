package com.example.ai.server

import com.example.ai.api.ApiRoutes
import com.example.ai.api.ParsedTodoDto
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `POST /parse` through `testApplication` with a fake [LlmClient]: what the route does around the
 * model (validation, error mapping, post-processing), not what a real model says.
 */
class ParseRoutesTest {

    private class FakeLlm(var answer: suspend (String) -> ParsedTodoDto) : LlmClient {
        val calls = mutableListOf<String>()

        override suspend fun parse(text: String): ParsedTodoDto {
            calls += text
            return answer(text)
        }
    }

    private val milk = ParsedTodoDto(title = "Buy milk and bread", description = "Tomorrow morning, before work")

    private fun parseTest(llm: LlmClient, block: suspend HttpClient.() -> Unit) = testApplication {
        application { module(llm = llm) }
        client.block()
    }

    private suspend fun HttpClient.parse(text: String): HttpResponse =
        parseRaw(buildJsonObject { put("text", text) }.toString())

    private suspend fun HttpClient.parseRaw(body: String): HttpResponse =
        post(ApiRoutes.PARSE) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.draft(): ParsedTodoDto = Json.decodeFromString(bodyAsText())

    // ---- happy path ----------------------------------------------------------------------------

    @Test
    fun aSentenceBecomesADraft() {
        val llm = FakeLlm { milk }
        parseTest(llm) {
            val response = parse("uh, buy milk and bread, tomorrow morning before work")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(milk, response.draft())
            assertEquals(listOf("uh, buy milk and bread, tomorrow morning before work"), llm.calls)
        }
    }

    @Test
    fun thePolishAndEmojiSurviveTheTrip() {
        val llm = FakeLlm { ParsedTodoDto("Kup mleko 🥛", "Jutro rano, przed pracą: zażółć gęślą jaźń") }
        parseTest(llm) {
            val response = parse("kup mleko 🥛")

            assertEquals(ParsedTodoDto("Kup mleko 🥛", "Jutro rano, przed pracą: zażółć gęślą jaźń"), response.draft())
        }
    }

    @Test
    fun theModelGetsTheTrimmedText() {
        val llm = FakeLlm { milk }
        parseTest(llm) {
            parse("  \n buy milk \t ")

            assertEquals(listOf("buy milk"), llm.calls)
        }
    }

    // ---- 400 -----------------------------------------------------------------------------------

    @Test
    fun blankTextIsABadRequestAndTheModelIsNotAsked() {
        val llm = FakeLlm { milk }
        parseTest(llm) {
            for (blank in listOf("", " ", "\n\t  ")) {
                assertEquals(HttpStatusCode.BadRequest, parse(blank).status, "text='$blank'")
            }
            assertEquals(emptyList(), llm.calls)
        }
    }

    @Test
    fun exactlyTheLimitIsAcceptedAndOneMoreIsNot() {
        val llm = FakeLlm { milk }
        parseTest(llm) {
            val atLimit = "a".repeat(ApiRoutes.PARSE_MAX_TEXT_LENGTH)

            assertEquals(HttpStatusCode.OK, parse(atLimit).status)
            assertEquals(HttpStatusCode.BadRequest, parse(atLimit + "a").status)
            assertEquals(listOf(atLimit), llm.calls) // the rejected one never reached the model
        }
    }

    @Test
    fun theLimitIsMeasuredOnTheTrimmedText() {
        val llm = FakeLlm { milk }
        parseTest(llm) {
            val padded = "  " + "a".repeat(ApiRoutes.PARSE_MAX_TEXT_LENGTH) + "  "

            assertEquals(HttpStatusCode.OK, parse(padded).status)
        }
    }

    @Test
    fun aBodyWithoutTextOrWithBrokenJsonIsABadRequest() {
        val llm = FakeLlm { milk }
        parseTest(llm) {
            assertEquals(HttpStatusCode.BadRequest, parseRaw("{}").status)
            assertEquals(HttpStatusCode.BadRequest, parseRaw("""{"text":""").status)
            assertEquals(HttpStatusCode.BadRequest, parseRaw("""{"text":5}""").status)
            assertEquals(emptyList(), llm.calls)
        }
    }

    @Test
    fun unknownFieldsInTheBodyAreIgnored() {
        parseTest(FakeLlm { milk }) {
            assertEquals(HttpStatusCode.OK, parseRaw("""{"text":"buy milk","language":"en"}""").status)
        }
    }

    // ---- 503 / 502 -----------------------------------------------------------------------------

    @Test
    fun anUnavailableModelIs503() {
        parseTest(FakeLlm { throw LlmUnavailableException("connection refused") }) {
            assertEquals(HttpStatusCode.ServiceUnavailable, parse("buy milk").status)
        }
    }

    @Test
    fun garbageFromTheModelIs502() {
        parseTest(FakeLlm { throw LlmBadOutputException("not json") }) {
            assertEquals(HttpStatusCode.BadGateway, parse("buy milk").status)
        }
    }

    @Test
    fun withoutAnyLlmConfiguredTheEndpointIs503() = testApplication {
        // `module()` with its default: what every other test of this server runs with.
        application { module() }

        assertEquals(HttpStatusCode.ServiceUnavailable, client.parse("buy milk").status)
    }

    // ---- post-processing -----------------------------------------------------------------------

    @Test
    fun aLongTitleIsCapped() {
        val long = "word ".repeat(40) // 200 characters
        parseTest(FakeLlm { ParsedTodoDto(long, "details") }) {
            val draft = parse("something").draft()

            assertTrue(draft.title.length <= ApiRoutes.PARSE_MAX_TITLE_LENGTH, "was ${draft.title.length}")
            assertEquals("details", draft.description)
        }
    }

    @Test
    fun aBlankTitleBecomesTheFirstWordsOfTheInput() {
        parseTest(FakeLlm { ParsedTodoDto("   ", "") }) {
            val draft = parse("remember to call the dentist about the appointment next week please").draft()

            assertEquals("remember to call the dentist about the appointment", draft.title)
        }
    }

    @Test
    fun fieldsAreTrimmed() {
        parseTest(FakeLlm { ParsedTodoDto("  Buy milk \n", "\n 2 l  ") }) {
            assertEquals(ParsedTodoDto("Buy milk", "2 l"), parse("milk").draft())
        }
    }
}
