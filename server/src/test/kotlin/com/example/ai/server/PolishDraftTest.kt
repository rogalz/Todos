package com.example.ai.server

import com.example.ai.api.ApiRoutes
import com.example.ai.api.ParsedTodoDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The post-processing on its own, including the edges the route tests do not bother with. */
class PolishDraftTest {

    private val max = ApiRoutes.PARSE_MAX_TITLE_LENGTH

    private fun polish(input: String = "input", title: String, description: String = "") =
        polishDraft(input, ParsedTodoDto(title, description))

    @Test
    fun aTitleAtTheLimitIsKeptAsItIs() {
        val title = "a".repeat(max)

        assertEquals(title, polish(title = title).title)
    }

    @Test
    fun aTitleOneOverTheLimitIsCut() {
        assertEquals("a".repeat(max), polish(title = "a".repeat(max + 1)).title)
    }

    @Test
    fun theCutDoesNotLeaveATrailingSpace() {
        val title = "a".repeat(max - 1) + " tail"

        assertEquals("a".repeat(max - 1), polish(title = title).title)
    }

    @Test
    fun theCutNeverSplitsAnEmojiInHalf() {
        // The rocket is two chars (a surrogate pair) and starts at index max - 1, so a plain cut at
        // `max` would keep only its first half, which is not valid text.
        val title = "a".repeat(max - 1) + "🚀" + "tail"

        val capped = polish(title = title).title

        assertEquals("a".repeat(max - 1), capped)
        assertFalse(capped.last().isHighSurrogate())
    }

    @Test
    fun anEmojiThatFitsIsKept() {
        val title = "a".repeat(max - 2) + "🚀"

        assertEquals(title, polish(title = title).title)
    }

    @Test
    fun aBlankTitleUsesTheFirstEightWordsOfTheInput() {
        val result = polish(input = "  one two   three four five six seven eight nine ten ", title = " \n ")

        assertEquals("one two three four five six seven eight", result.title)
    }

    @Test
    fun theFallbackTitleIsCappedToo() {
        val result = polish(input = "x".repeat(500), title = "")

        assertEquals(max, result.title.length)
    }

    @Test
    fun aShortInputIsUsedWhole() {
        assertEquals("milk", polish(input = "milk", title = "").title)
    }

    @Test
    fun theDescriptionIsTrimmedButNeverCapped() {
        val long = "d".repeat(5000)

        assertEquals(long, polish(title = "t", description = "  $long\n").description)
        assertTrue(polish(title = "t", description = "   ").description.isEmpty())
    }
}
