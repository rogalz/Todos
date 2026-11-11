package com.example.ai.data

import com.example.ai.api.ParsedTodoDto
import com.example.ai.domain.TodoDraft
import com.example.ai.testing.FakeTodoApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApiTodoDraftParserTest {

    @Test
    fun theServersAnswerBecomesTheDraft() = runTest {
        val api = FakeTodoApi().apply { onParse = { ParsedTodoDto("Buy milk", "2 l") } }

        val draft = ApiTodoDraftParser(api).parse("uh, buy milk, two litres")

        assertEquals(TodoDraft("Buy milk", "2 l"), draft)
        assertEquals(listOf("uh, buy milk, two litres"), api.parses)
    }

    @Test
    fun aFailureOfTheServerIsNotHidden() = runTest {
        val api = FakeTodoApi().apply { onParse = { throw IllegalStateException("503") } }

        assertFailsWith<IllegalStateException> { ApiTodoDraftParser(api).parse("x") }
    }
}
