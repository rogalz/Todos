package com.example.ai.domain

/** Turns what the user said into a [TodoDraft]. Any failure (offline, a server error, a timeout) is an exception. */
interface TodoDraftParser {
    suspend fun parse(text: String): TodoDraft
}
