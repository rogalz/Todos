package com.example.ai.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A token that was forgotten falls back to Material's default (purple) baseline. This compares every
 * token of our schemes with `lightColorScheme()` / `darkColorScheme()`.
 *
 * The tokens are found by reflection (JVM-only, hence this is a `jvmTest`) so that a token Material adds
 * in a later version is covered without anyone remembering to extend a list.
 */
class ThemeDefaultsTest {

    /** `Color` is an inline class over a `ULong`, so a token's getter returns the raw `long`. */
    private fun tokens(scheme: ColorScheme): Map<String, Long> =
        ColorScheme::class.java.declaredMethods
            .filter {
                Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE &&
                    it.name.startsWith("get") && '$' !in it.name
            }
            // Inline-class getters are mangled: "getPrimary-0d7_KjU" -> "Primary".
            .associate { it.name.removePrefix("get").substringBefore('-') to it.invoke(scheme) as Long }

    private fun assertNoDefaultLeaks(schemeName: String, ours: ColorScheme, default: ColorScheme, allowed: Set<String>) {
        val ourTokens = tokens(ours)
        val defaultTokens = tokens(default)
        // Guards against passing vacuously if reflection stops finding the getters.
        assertTrue(ourTokens.size >= 40, "found only ${ourTokens.size} colour tokens; did ColorScheme change shape?")
        assertTrue(ourTokens.keys == defaultTokens.keys)

        val leaking = ourTokens.filter { (name, value) -> value == defaultTokens.getValue(name) && name !in allowed }.keys
        assertTrue(leaking.isEmpty(), "$schemeName tokens still equal to Material's default: $leaking")

        // The allow-list must not rot: an entry that no longer equals the default should be removed.
        val staleAllowed = allowed.filter { ourTokens[it] != defaultTokens[it] }.toSet().let { it - leaking }
        assertTrue(staleAllowed.isEmpty(), "$schemeName allow-list entries that differ from the default now: $staleAllowed")
    }

    @Test
    fun lightSchemeDefinesEveryTokenItself() = assertNoDefaultLeaks(
        schemeName = "light",
        ours = LightColorScheme,
        default = lightColorScheme(),
        // White text on a coloured fill, and the spec's error red: they are specified values that
        // happen to be the Material baseline too, not leaks.
        allowed = setOf("OnPrimary", "OnSecondary", "OnTertiary", "OnError", "Error"),
    )

    @Test
    fun darkSchemeDefinesEveryTokenItself() = assertNoDefaultLeaks(
        schemeName = "dark",
        ours = DarkColorScheme,
        default = darkColorScheme(),
        // The spec's dark error colour (#F2B8B5) is the Material baseline value as well.
        allowed = setOf("Error"),
    )
}
