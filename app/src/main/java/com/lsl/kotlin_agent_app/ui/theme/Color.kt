package com.lsl.kotlin_agent_app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val mdPrimary = Color(0xFF1B6B4A)
private val mdOnPrimary = Color(0xFFFFFFFF)
private val mdPrimaryContainer = Color(0xFFA7F5C8)
private val mdOnPrimaryContainer = Color(0xFF002113)
private val mdSecondary = Color(0xFF4E6355)
private val mdOnSecondary = Color(0xFFFFFFFF)
private val mdSecondaryContainer = Color(0xFFD0E8D6)
private val mdOnSecondaryContainer = Color(0xFF0B1F14)
private val mdTertiary = Color(0xFF3C6472)
private val mdOnTertiary = Color(0xFFFFFFFF)
private val mdSurface = Color(0xFFF6FBF3)
private val mdOnSurface = Color(0xFF171D19)
private val mdSurfaceVariant = Color(0xFFDCE5DB)
private val mdOnSurfaceVariant = Color(0xFF414942)
private val mdError = Color(0xFFBA1A1A)
private val mdOnError = Color(0xFFFFFFFF)
private val mdOutline = Color(0xFF717971)

val LightColorScheme =
    lightColorScheme(
        primary = mdPrimary,
        onPrimary = mdOnPrimary,
        primaryContainer = mdPrimaryContainer,
        onPrimaryContainer = mdOnPrimaryContainer,
        secondary = mdSecondary,
        onSecondary = mdOnSecondary,
        secondaryContainer = mdSecondaryContainer,
        onSecondaryContainer = mdOnSecondaryContainer,
        tertiary = mdTertiary,
        onTertiary = mdOnTertiary,
        surface = mdSurface,
        onSurface = mdOnSurface,
        surfaceVariant = mdSurfaceVariant,
        onSurfaceVariant = mdOnSurfaceVariant,
        error = mdError,
        onError = mdOnError,
        outline = mdOutline,
    )

val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF8BD8A8),
        onPrimary = Color(0xFF003822),
        primaryContainer = Color(0xFF005234),
        onPrimaryContainer = Color(0xFFA7F5C8),
        secondary = Color(0xFFB5CCBB),
        onSecondary = Color(0xFF203528),
        secondaryContainer = Color(0xFF374B3E),
        onSecondaryContainer = Color(0xFFD0E8D6),
        tertiary = Color(0xFFA3CDDB),
        onTertiary = Color(0xFF033541),
        surface = Color(0xFF0F1511),
        onSurface = Color(0xFFDFE4DD),
        surfaceVariant = Color(0xFF414942),
        onSurfaceVariant = Color(0xFFC0C9BF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        outline = Color(0xFF8B938A),
    )

