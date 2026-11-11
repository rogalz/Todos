package com.example.ai.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * WCAG 2.2 AA for every pair of colours that carries text or an icon that has to be read: 4.5:1.
 * Container pairs (`onPrimaryContainer` on `primaryContainer`, ...) are included because widgets put
 * text on them (the sync chip, the FAB, snackbars, dialogs).
 *
 * Not checked: `outline` / `outlineVariant`. They are decorative hairlines by design (see Color.kt);
 * nothing that has to be recognised may depend on them.
 */
class ThemeContrastTest {

    private class Pair(val name: String, val foreground: Color, val background: Color) {
        val ratio: Double get() = contrastRatio(foreground, background)
    }

    private fun ColorScheme.textPairs(): List<Pair> = buildList {
        fun add(name: String, foreground: Color, background: Color) = add(Pair(name, foreground, background))

        add("onPrimary on primary", onPrimary, primary)
        add("onPrimaryContainer on primaryContainer", onPrimaryContainer, primaryContainer)
        add("onSecondary on secondary", onSecondary, secondary)
        add("onSecondaryContainer on secondaryContainer", onSecondaryContainer, secondaryContainer)
        add("onTertiary on tertiary", onTertiary, tertiary)
        add("onTertiaryContainer on tertiaryContainer", onTertiaryContainer, tertiaryContainer)
        add("onError on error", onError, error)
        add("onErrorContainer on errorContainer", onErrorContainer, errorContainer)
        add("inverseOnSurface on inverseSurface", inverseOnSurface, inverseSurface)
        // The snackbar action ("Discard") is drawn in inversePrimary on inverseSurface.
        add("inversePrimary on inverseSurface", inversePrimary, inverseSurface)
        add("onBackground on background", onBackground, background)

        val surfaces = listOf(
            "background" to background,
            "surface" to surface,
            "surfaceVariant" to surfaceVariant,
            "surfaceDim" to surfaceDim,
            "surfaceBright" to surfaceBright,
            "surfaceContainerLowest" to surfaceContainerLowest,
            "surfaceContainerLow" to surfaceContainerLow,
            "surfaceContainer" to surfaceContainer,
            "surfaceContainerHigh" to surfaceContainerHigh,
            "surfaceContainerHighest" to surfaceContainerHighest,
        )
        for ((surfaceName, surfaceColor) in surfaces) {
            add("onSurface on $surfaceName", onSurface, surfaceColor)
            add("onSurfaceVariant on $surfaceName", onSurfaceVariant, surfaceColor)
        }

        // Accent colours used as text or icons directly on the page (text buttons, the offline chip, errors).
        for ((surfaceName, surfaceColor) in surfaces.filter { it.first == "background" || it.first == "surface" }) {
            add("primary on $surfaceName", primary, surfaceColor)
            add("secondary on $surfaceName", secondary, surfaceColor)
            add("error on $surfaceName", error, surfaceColor)
        }

        add("onPrimaryFixed on primaryFixed", onPrimaryFixed, primaryFixed)
        add("onPrimaryFixedVariant on primaryFixed", onPrimaryFixedVariant, primaryFixed)
        add("onSecondaryFixed on secondaryFixed", onSecondaryFixed, secondaryFixed)
        add("onSecondaryFixedVariant on secondaryFixed", onSecondaryFixedVariant, secondaryFixed)
        add("onTertiaryFixed on tertiaryFixed", onTertiaryFixed, tertiaryFixed)
        add("onTertiaryFixedVariant on tertiaryFixed", onTertiaryFixedVariant, tertiaryFixed)
    }

    private fun assertAllPass(schemeName: String, scheme: ColorScheme) {
        val pairs = scheme.textPairs()
        // The report quotes these numbers; printing them keeps the report and the test from drifting apart.
        println("Contrast ($schemeName):")
        pairs.forEach { println("  ${(it.ratio * 100).toInt() / 100.0}  ${it.name}") }

        val failing = pairs.filter { it.ratio < AA_BODY_TEXT }
        assertTrue(
            failing.isEmpty(),
            "$schemeName pairs below $AA_BODY_TEXT:1: " +
                failing.joinToString { "${it.name} = ${(it.ratio * 100).toInt() / 100.0}" },
        )
    }

    @Test
    fun lightSchemeMeetsAaForEveryTextPair() = assertAllPass("light", LightColorScheme)

    @Test
    fun darkSchemeMeetsAaForEveryTextPair() = assertAllPass("dark", DarkColorScheme)

    @Test
    fun contrastRatioMatchesKnownValues() {
        // Black on white is the WCAG maximum, a colour against itself the minimum.
        assertEquals21(contrastRatio(Color.Black, Color.White))
        assertTrue(contrastRatio(Color.White, Color.White) == 1.0)
        // Symmetric: it does not matter which one is the foreground.
        assertTrue(contrastRatio(Color.Black, Color.White) == contrastRatio(Color.White, Color.Black))
        // #767676 on white is the textbook "just passes AA" grey (4.54:1).
        val ratio = contrastRatio(Color(0xFF767676), Color.White)
        assertTrue(ratio in 4.5..4.6, "expected about 4.54, was $ratio")
    }

    private fun assertEquals21(ratio: Double) = assertTrue(ratio > 20.99 && ratio < 21.01, "expected 21, was $ratio")

    private companion object {
        const val AA_BODY_TEXT = 4.5

        // WCAG 2.x definition: relative luminance from linearised sRGB, ratio = (L1 + 0.05) / (L2 + 0.05).
        // Written out instead of using Color.luminance() so the formula under test is visible and exact.
        fun channel(value: Float): Double {
            val v = value.toDouble()
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }

        fun luminance(color: Color): Double =
            0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

        fun contrastRatio(a: Color, b: Color): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }
    }
}
