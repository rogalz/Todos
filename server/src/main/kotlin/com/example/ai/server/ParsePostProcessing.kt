package com.example.ai.server

import com.example.ai.api.ApiRoutes
import com.example.ai.api.ParsedTodoDto

/** How many words of the input become the title when the model returns a blank one. */
private const val FALLBACK_TITLE_WORDS = 8

private val whitespace = Regex("\\s+")

/**
 * Makes the model's [raw] answer safe to show: trims both fields, falls back to the first words of
 * [input] when the title is blank, and caps the title at [ApiRoutes.PARSE_MAX_TITLE_LENGTH].
 *
 * The model is asked for a title of at most 60 characters, but a prompt is a request and not a
 * guarantee, so the cap is enforced here. The description is left as it is (apart from trimming).
 */
internal fun polishDraft(input: String, raw: ParsedTodoDto): ParsedTodoDto {
    val title = raw.title.trim().ifEmpty { firstWords(input) }
    return ParsedTodoDto(
        title = capTitle(title),
        description = raw.description.trim(),
    )
}

private fun firstWords(input: String): String =
    input.trim().split(whitespace).take(FALLBACK_TITLE_WORDS).joinToString(" ")

/** Cuts at the limit without splitting an emoji (a surrogate pair) in half, then drops a dangling space. */
private fun capTitle(title: String): String {
    val max = ApiRoutes.PARSE_MAX_TITLE_LENGTH
    if (title.length <= max) return title
    val splitsAPair = title[max - 1].isHighSurrogate() && title[max].isLowSurrogate()
    return title.substring(0, if (splitsAPair) max - 1 else max).trimEnd()
}
