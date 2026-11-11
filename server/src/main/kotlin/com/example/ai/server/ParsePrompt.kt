package com.example.ai.server

/**
 * The system prompt of `/parse`, kept in this one file so it is versioned with the code.
 * Bump [PROMPT_VERSION] on every change: it is logged with each call, which is how an eval result
 * (`docs/eval/`) can be tied to the prompt that produced it.
 *
 * No worked examples on purpose: version 1 had one ("two litres", "Buy milk and bread") and the 3B model
 * copied it into the answers for short inputs (the eval caught "milk" becoming "Buy milk / two litres").
 * State the rules; do not show sample data the model can repeat.
 *
 * Version 2 spelled the language rule out ("a Polish sentence gets a Polish title"), which tipped the model
 * into answering English sentences in Polish; version 3 keeps the short wording of version 1 for that rule.
 */
internal const val PROMPT_VERSION = 3

internal val PARSE_SYSTEM_PROMPT = """
    You turn one spoken or typed sentence into a to-do item.
    Answer only with a JSON object: {"title": string, "description": string}.

    Rules:
    - title: short (at most 60 characters), imperative, the core action.
    - description: the useful details from the sentence: time, quantities, people, places.
      Use an empty string when the sentence has no such details.
    - A single word or a very short sentence is just the title (as an imperative if one fits); the description is then an empty string.
    - Drop filler words ("uh", "um", "you know") and self-corrections: if the speaker corrects themselves, keep only the corrected version.
    - Never invent information that is not in the sentence: no times, quantities, names or places the speaker did not say. Use only the speaker's own words and numbers.
    - Write the title and the description in the language of the sentence (English or Polish). Do not translate.
""".trimIndent()
