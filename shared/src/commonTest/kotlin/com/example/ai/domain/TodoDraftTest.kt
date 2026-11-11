package com.example.ai.domain

import com.example.ai.api.ApiRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TodoDraftTest {

    private val max = TodoDraft.MAX_TITLE_LENGTH

    @Test
    fun theTitleLimitIsTheOneTheServerUses() {
        assertEquals(ApiRoutes.PARSE_MAX_TITLE_LENGTH, TodoDraft.MAX_TITLE_LENGTH)
    }

    @Test
    fun theFallbackIsTheUsersOwnWordsAsTheTitleWithoutDescription() {
        assertEquals(TodoDraft("buy milk and bread", ""), TodoDraft.fallbackFor("buy milk and bread"))
    }

    @Test
    fun theFallbackIsTrimmedAndOnOneLine() {
        assertEquals("buy milk and bread", TodoDraft.fallbackFor("  buy milk\n and \t bread \n").title)
    }

    @Test
    fun aLongTranscriptIsCappedWithoutATrailingSpace() {
        val transcript = "a".repeat(max - 1) + " tail"

        assertEquals("a".repeat(max - 1), TodoDraft.fallbackFor(transcript).title)
    }

    @Test
    fun aTranscriptOfExactlyTheLimitIsKept() {
        assertEquals("a".repeat(max), TodoDraft.fallbackFor("a".repeat(max)).title)
    }

    @Test
    fun theCapNeverSplitsAnEmoji() {
        val capped = TodoDraft.fallbackFor("a".repeat(max - 1) + "🚀 and more").title

        assertEquals("a".repeat(max - 1), capped)
        assertFalse(capped.last().isHighSurrogate())
    }

    @Test
    fun polishCharactersSurvive() {
        assertEquals("Zażółć gęślą jaźń", TodoDraft.fallbackFor(" Zażółć gęślą jaźń ").title)
    }
}
