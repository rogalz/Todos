package com.example.ai.server

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The prompt is plain text, so nothing else notices when a rule of the spec is edited away. These
 * only pin that each rule is still stated; whether the model obeys is what `scripts/eval-parse.py` measures.
 */
class ParsePromptTest {

    private val prompt = PARSE_SYSTEM_PROMPT.lowercase()

    @Test
    fun itStatesEveryRuleOfTheSpec() {
        for (rule in listOf("60 characters", "imperative", "description", "filler", "self-correction", "never invent", "polish", "do not translate")) {
            assertTrue(rule in prompt, "the prompt no longer mentions '$rule'")
        }
    }

    @Test
    fun itAsksForTheJsonShapeOfTheSchema() {
        assertTrue("\"title\"" in PARSE_SYSTEM_PROMPT && "\"description\"" in PARSE_SYSTEM_PROMPT)
    }

    @Test
    fun itHasNoWorkedExampleTheSmallModelCouldCopyIntoItsAnswers() {
        // Version 1 had "two litres" and "Buy milk and bread" in it; see the note on PARSE_SYSTEM_PROMPT.
        for (sample in listOf("two litres", "milk", "bread")) {
            assertTrue(sample !in prompt, "sample data '$sample' is back in the prompt")
        }
    }

    @Test
    fun theVersionIsBumpedWhenThePromptChanges() {
        assertTrue(PROMPT_VERSION >= 2)
    }
}
