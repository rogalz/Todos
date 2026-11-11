package com.example.ai.server

import com.example.ai.api.ApiRoutes
import com.example.ai.api.ParseRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.parseRoutes(llm: LlmClient) {
    post(ApiRoutes.PARSE) {
        val request = call.receive<ParseRequest>()
        // Trimmed first: the limit protects the model's context, and the trimmed text is what it gets.
        val text = request.text.trim()
        if (text.isEmpty()) {
            return@post call.respondText("text must not be blank", status = HttpStatusCode.BadRequest)
        }
        if (text.length > ApiRoutes.PARSE_MAX_TEXT_LENGTH) {
            return@post call.respondText(
                "text must be at most ${ApiRoutes.PARSE_MAX_TEXT_LENGTH} characters",
                status = HttpStatusCode.BadRequest,
            )
        }

        val raw = try {
            llm.parse(text)
        } catch (e: LlmUnavailableException) {
            call.application.environment.log.warn("parse: LLM unavailable: {}", e.message)
            return@post call.respondText("the language model is not available", status = HttpStatusCode.ServiceUnavailable)
        } catch (e: LlmBadOutputException) {
            call.application.environment.log.warn("parse: unusable LLM output: {}", e.message)
            return@post call.respondText("the language model returned an unusable answer", status = HttpStatusCode.BadGateway)
        }

        call.respond(polishDraft(text, raw))
    }
}
