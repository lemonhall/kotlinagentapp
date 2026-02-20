package com.lsl.kotlin_agent_app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val mdDarkBackground = Color(0xFF1A1A2E)
private val mdDarkSurfaceVariant = Color(0xFF16213E)
private val mdDarkOnBackground = Color(0xFFE7E1D9)
private val mdDarkOnSurfaceVariant = Color(0xFFCDC6B4)

private val mdAccent = Color(0xFFFFF44F)
private val mdAccentContainer = Color(0xFFF0D000)
private val mdAccentSoft = Color(0xFFE4C523)

private val mdLightBackground = Color(0xFFFFFBFF)
private val mdLightOnBackground = Color(0xFF1D1B16)
private val mdLightPrimary = Color(0xFF6D5E00)

val LightColorScheme =
    lightColorScheme(
        primary = mdLightPrimary,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = mdAccent,
        onPrimaryContainer = mdLightOnBackground,
        secondary = mdDarkSurfaceVariant,
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE7E1D9),
        onSecondaryContainer = mdLightOnBackground,
        tertiary = mdDarkBackground,
        onTertiary = Color(0xFFFFFFFF),
        background = mdLightBackground,
        onBackground = mdLightOnBackground,
        surface = mdLightBackground,
        onSurface = mdLightOnBackground,
        surfaceVariant = Color(0xFFE7E1D9),
        onSurfaceVariant = Color(0xFF3B382D),
        outline = Color(0xFF7A745D),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
    )

val DarkColorScheme =
    darkColorScheme(
        primary = mdAccent,
        onPrimary = mdDarkBackground,
        primaryContainer = mdAccentContainer,
        onPrimaryContainer = mdDarkBackground,
        secondary = mdAccentSoft,
        onSecondary = mdDarkBackground,
        secondaryContainer = mdDarkSurfaceVariant,
        onSecondaryContainer = mdAccent,
        tertiary = mdDarkOnBackground,
        onTertiary = mdDarkBackground,
        background = mdDarkBackground,
        onBackground = mdDarkOnBackground,
        surface = mdDarkBackground,
        onSurface = mdDarkOnBackground,
        surfaceVariant = mdDarkSurfaceVariant,
        onSurfaceVariant = mdDarkOnSurfaceVariant,
        outline = Color(0xFF6D6A7E),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )
