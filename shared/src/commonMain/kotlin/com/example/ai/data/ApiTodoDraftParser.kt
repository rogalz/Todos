package com.example.ai.data

import com.example.ai.data.remote.TodoApi
import com.example.ai.domain.TodoDraft
import com.example.ai.domain.TodoDraftParser

/** [TodoDraftParser] backed by the server's `POST /parse` (which asks the local LLM). */
class ApiTodoDraftParser(private val api: TodoApi) : TodoDraftParser {
    override suspend fun parse(text: String): TodoDraft {
        val parsed = api.parse(text)
        return TodoDraft(title = parsed.title, description = parsed.description)
    }
}
