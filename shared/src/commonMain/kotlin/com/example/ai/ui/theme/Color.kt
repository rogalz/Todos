package com.example.ai.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * The only place in the app that contains colour values. Screens read `MaterialTheme.colorScheme`.
 *
 * Both schemes name EVERY token on purpose. A token left out silently falls back to Material's default
 * purple baseline (the FAB, for example, is `primaryContainer` unless told otherwise), so a missing one
 * is a visible bug. ThemeDefaultsTest fails if any token still equals the default, and
 * ThemeContrastTest checks every pair that carries text against WCAG AA (4.5:1).
 *
 * The ten colours of the stage 5 spec are used as given, except `secondary` in the light scheme: the
 * specified honey (#C98A1E) is only 2.9:1 on the surface, too low for the chip's icon and text, so it
 * is #8F5E0A (5.4:1). `outline` is 1.6:1 (light) and 2.2:1 (dark): it is only ever decorative (hairlines);
 * nothing that has to be recognised may rely on it.
 */

// ---- Light ----

private val LightPrimary = Color(0xFFB5532E) // terracotta
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFFFDBCF)
private val LightOnPrimaryContainer = Color(0xFF3B1000)
private val LightInversePrimary = Color(0xFFF0A07E)

private val LightSecondary = Color(0xFF8F5E0A) // honey, darkened from the spec's #C98A1E for contrast
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFFFDEA6)
private val LightOnSecondaryContainer = Color(0xFF2B1A00)

private val LightTertiary = Color(0xFF5E6B36) // sage: a calm third accent, not used by the screens yet
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFE2EBB5)
private val LightOnTertiaryContainer = Color(0xFF1B2300)

private val LightBackground = Color(0xFFFFF8F1) // cream
private val LightOnBackground = Color(0xFF2D211A)
private val LightSurface = Color(0xFFFFFCF8)
private val LightOnSurface = Color(0xFF2D211A) // warm brown
private val LightSurfaceVariant = Color(0xFFF2E5D7) // sand
private val LightOnSurfaceVariant = Color(0xFF6B5A4E)
private val LightSurfaceTint = LightPrimary

private val LightSurfaceDim = Color(0xFFE9DDD1)
private val LightSurfaceBright = Color(0xFFFFFCF8)
private val LightSurfaceContainerLowest = Color(0xFFFFFDFA)
private val LightSurfaceContainerLow = Color(0xFFFBF3EA)
private val LightSurfaceContainer = Color(0xFFF7EEE4)
private val LightSurfaceContainerHigh = Color(0xFFF2E5D7)
private val LightSurfaceContainerHighest = Color(0xFFEDDFD0)

private val LightInverseSurface = Color(0xFF3A3029)
private val LightInverseOnSurface = Color(0xFFF8EDE4)

private val LightError = Color(0xFFB3261E)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFBDCD3)
private val LightOnErrorContainer = Color(0xFF3F0F0A)

private val LightOutline = Color(0xFFD8C6B6) // decorative only, see the note above
private val LightOutlineVariant = Color(0xFFEADCCD)
private val LightScrim = Color(0xFF1F140D)

// ---- Dark ----

private val DarkPrimary = Color(0xFFF0A07E)
private val DarkOnPrimary = Color(0xFF4A1A08)
private val DarkPrimaryContainer = Color(0xFF7A3519)
private val DarkOnPrimaryContainer = Color(0xFFFFDBCF)
private val DarkInversePrimary = Color(0xFF9C4222)

private val DarkSecondary = Color(0xFFE8B65A)
private val DarkOnSecondary = Color(0xFF3E2A00)
private val DarkSecondaryContainer = Color(0xFF5C3F0A)
private val DarkOnSecondaryContainer = Color(0xFFFFDEA6)

private val DarkTertiary = Color(0xFFC5CF8F)
private val DarkOnTertiary = Color(0xFF2F3600)
private val DarkTertiaryContainer = Color(0xFF46501F)
private val DarkOnTertiaryContainer = Color(0xFFE2EBB5)

private val DarkBackground = Color(0xFF1C1612)
private val DarkOnBackground = Color(0xFFF2E6DC)
private val DarkSurface = Color(0xFF26201B)
private val DarkOnSurface = Color(0xFFF2E6DC)
private val DarkSurfaceVariant = Color(0xFF3A3029)
private val DarkOnSurfaceVariant = Color(0xFFCDBCAF)
private val DarkSurfaceTint = DarkPrimary

private val DarkSurfaceDim = Color(0xFF17110D)
private val DarkSurfaceBright = Color(0xFF3E352E)
private val DarkSurfaceContainerLowest = Color(0xFF140F0B)
private val DarkSurfaceContainerLow = Color(0xFF221B16)
private val DarkSurfaceContainer = Color(0xFF2A231E)
private val DarkSurfaceContainerHigh = Color(0xFF342C26)
private val DarkSurfaceContainerHighest = Color(0xFF3F3731)

private val DarkInverseSurface = Color(0xFFF2E6DC)
private val DarkInverseOnSurface = Color(0xFF2D211A)

private val DarkError = Color(0xFFF2B8B5)
private val DarkOnError = Color(0xFF5A130F)
private val DarkErrorContainer = Color(0xFF861E19)
private val DarkOnErrorContainer = Color(0xFFFBDCD3)

private val DarkOutline = Color(0xFF5A4C42) // decorative only, see the note above
private val DarkOutlineVariant = Color(0xFF463A32)
private val DarkScrim = Color(0xFF0F0A06)

// ---- "Fixed" roles: identical in both schemes by definition ----

private val PrimaryFixed = Color(0xFFFFDBCF)
private val PrimaryFixedDim = Color(0xFFFFB59D)
private val OnPrimaryFixed = Color(0xFF3B1000)
private val OnPrimaryFixedVariant = Color(0xFF8A3A1A)
private val SecondaryFixed = Color(0xFFFFDEA6)
private val SecondaryFixedDim = Color(0xFFF0BE6A)
private val OnSecondaryFixed = Color(0xFF2B1A00)
private val OnSecondaryFixedVariant = Color(0xFF6A4600)
private val TertiaryFixed = Color(0xFFE2EBB5)
private val TertiaryFixedDim = Color(0xFFC6CF9B)
private val OnTertiaryFixed = Color(0xFF1B2300)
private val OnTertiaryFixedVariant = Color(0xFF46501F)

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = LightInversePrimary,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightSurfaceTint,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = LightScrim,
    surfaceBright = LightSurfaceBright,
    surfaceDim = LightSurfaceDim,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    primaryFixed = PrimaryFixed,
    primaryFixedDim = PrimaryFixedDim,
    onPrimaryFixed = OnPrimaryFixed,
    onPrimaryFixedVariant = OnPrimaryFixedVariant,
    secondaryFixed = SecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim,
    onSecondaryFixed = OnSecondaryFixed,
    onSecondaryFixedVariant = OnSecondaryFixedVariant,
    tertiaryFixed = TertiaryFixed,
    tertiaryFixedDim = TertiaryFixedDim,
    onTertiaryFixed = OnTertiaryFixed,
    onTertiaryFixedVariant = OnTertiaryFixedVariant,
)

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = DarkInversePrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkSurfaceTint,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = DarkScrim,
    surfaceBright = DarkSurfaceBright,
    surfaceDim = DarkSurfaceDim,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    primaryFixed = PrimaryFixed,
    primaryFixedDim = PrimaryFixedDim,
    onPrimaryFixed = OnPrimaryFixed,
    onPrimaryFixedVariant = OnPrimaryFixedVariant,
    secondaryFixed = SecondaryFixed,
    secondaryFixedDim = SecondaryFixedDim,
    onSecondaryFixed = OnSecondaryFixed,
    onSecondaryFixedVariant = OnSecondaryFixedVariant,
    tertiaryFixed = TertiaryFixed,
    tertiaryFixedDim = TertiaryFixedDim,
    onTertiaryFixed = OnTertiaryFixed,
    onTertiaryFixedVariant = OnTertiaryFixedVariant,
)
