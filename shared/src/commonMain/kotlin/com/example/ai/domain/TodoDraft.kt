package com.example.ai.domain

/**
 * A suggestion for a new todo, made from something the user said or typed. It is not a [Todo]: it
 * has no id and is not stored. The user reviews and edits it, and only an explicit Save turns it
 * into a real todo.
 */
data class TodoDraft(val title: String, val description: String) {

    companion object {
        /**
         * The longest title a draft may have. It is the same number the server caps its titles at
         * (`ApiRoutes.PARSE_MAX_TITLE_LENGTH`; a test keeps the two equal), repeated here because the
         * domain does not depend on the wire contract.
         */
        const val MAX_TITLE_LENGTH = 80

        /**
         * What the user sees when the AI could not be reached: their own words as the title, trimmed,
         * on one line and capped, with no description. Better than an empty form after dictating.
         */
        fun fallbackFor(transcript: String): TodoDraft {
            val oneLine = transcript.trim().replace(WHITESPACE, " ")
            val capped = if (oneLine.length <= MAX_TITLE_LENGTH) {
                oneLine
            } else {
                // Never cut an emoji (a surrogate pair) in half.
                val splitsAPair = oneLine[MAX_TITLE_LENGTH - 1].isHighSurrogate() && oneLine[MAX_TITLE_LENGTH].isLowSurrogate()
                oneLine.substring(0, if (splitsAPair) MAX_TITLE_LENGTH - 1 else MAX_TITLE_LENGTH).trimEnd()
            }
            return TodoDraft(title = capped, description = "")
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
