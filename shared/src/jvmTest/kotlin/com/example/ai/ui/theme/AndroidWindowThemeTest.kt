package com.example.ai.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Android *window* theme (what shows before Compose draws, and the system splash) is XML and so has
 * to repeat the background colour of the Compose theme. This keeps the two equal in both modes, and
 * keeps the window theme day/night-aware: a light-only parent made the window flash white in dark mode.
 *
 * It reads the app module's resource files (plain text), so it needs no Android tooling.
 */
class AndroidWindowThemeTest {

    private val androidRes: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "androidApp/src/main/res") }
        .firstOrNull { it.isDirectory }
        ?: error("androidApp/src/main/res not found above ${System.getProperty("user.dir")}")

    private fun windowBackgroundIn(valuesDir: String): String {
        val xml = File(androidRes, "$valuesDir/colors.xml").readText()
        val match = Regex("""<color\s+name="window_background">\s*(#[0-9A-Fa-f]{6})\s*</color>""").find(xml)
        return assertNotNull(match, "no window_background colour in $valuesDir/colors.xml").groupValues[1].uppercase()
    }

    private fun hexOf(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)

    @Test
    fun theLightWindowBackgroundIsTheLightBackgroundToken() {
        assertEquals(hexOf(LightColorScheme.background), windowBackgroundIn("values"))
    }

    @Test
    fun theDarkWindowBackgroundIsTheDarkBackgroundToken() {
        assertEquals(hexOf(DarkColorScheme.background), windowBackgroundIn("values-night"))
    }

    @Test
    fun theWindowThemeFollowsTheSystemDayNightSettingAndUsesThatBackground() {
        val themes = File(androidRes, "values/themes.xml").readText()

        assertTrue(
            Regex("""<style\s+name="Theme\.App"\s+parent="android:Theme\.[A-Za-z.]*DayNight[A-Za-z.]*"""").containsMatchIn(themes),
            "Theme.App must have a DayNight parent, or the window stays light in dark mode",
        )
        assertTrue(
            Regex("""android:windowBackground">@color/window_background<""").containsMatchIn(themes),
            "Theme.App must use @color/window_background as its window background",
        )
    }

    @Test
    fun theApplicationUsesThatTheme() {
        val manifest = File(androidRes.parentFile, "AndroidManifest.xml").readText()

        assertTrue("""android:theme="@style/Theme.App"""" in manifest, "the manifest must apply @style/Theme.App")
    }

    @Test
    fun theTwoModesReallyDiffer() {
        // Guards the comparison above against passing vacuously (e.g. both files holding the same colour).
        assertTrue(windowBackgroundIn("values") != windowBackgroundIn("values-night"))
    }
}
