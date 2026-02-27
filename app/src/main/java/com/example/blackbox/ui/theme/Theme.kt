package com.example.blackbox.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
fun BlackboxTheme(
    customAccentHex: String?,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = blackboxColorScheme(customAccentHex = customAccentHex),
        typography = Typography,
        content = content
    )
}

@Stable
fun normalizeAccentHex(input: String): String? {
    val sanitized = input.trim().removePrefix("#")
    return sanitized
        .takeIf { AccentHexRegex.matches(it) }
        ?.uppercase(Locale.US)
}

@Stable
fun accentColorFromHex(hex: String): Color {
    val normalizedHex = normalizeAccentHex(hex) ?: return TerminalGreen
    val red = normalizedHex.substring(0, 2).toInt(16) / 255f
    val green = normalizedHex.substring(2, 4).toInt(16) / 255f
    val blue = normalizedHex.substring(4, 6).toInt(16) / 255f
    return Color(red = red, green = green, blue = blue, alpha = 1f)
}

@Composable
private fun blackboxColorScheme(customAccentHex: String?): ColorScheme {
    val context = LocalContext.current
    val dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val baseScheme = if (dynamicColorAvailable) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme(
            primary = TerminalGreen,
            secondary = TerminalGreen.copy(alpha = 0.84f),
            tertiary = TerminalGreen.copy(alpha = 0.68f)
        )
    }

    val customAccentColor = customAccentHex
        ?.let(::normalizeAccentHex)
        ?.let(::accentColorFromHex)

    val accentColor = customAccentColor
        ?: if (dynamicColorAvailable) baseScheme.primary else TerminalGreen

    return baseScheme.copy(
        primary = accentColor,
        onPrimary = colorForForeground(accentColor),
        primaryContainer = lerp(NeoSurface, accentColor, 0.22f),
        onPrimaryContainer = Color.White,
        secondary = customAccentColor?.let { accentColor.copy(alpha = 0.84f) } ?: baseScheme.secondary,
        tertiary = customAccentColor?.let { accentColor.copy(alpha = 0.68f) } ?: baseScheme.tertiary,
        background = NeoBackground,
        onBackground = Color.White,
        surface = NeoSurface,
        onSurface = Color.White,
        surfaceVariant = NeoSurfaceVariant,
        onSurfaceVariant = Color(0xFFDADADA),
        outline = lerp(NeoSurface, Color.White, 0.18f),
        outlineVariant = lerp(NeoSurface, Color.Black, 0.26f)
    )
}

private fun colorForForeground(background: Color): Color {
    return if (background.luminance() >= 0.5f) Color.Black else Color.White
}

private val AccentHexRegex = Regex("^[0-9A-Fa-f]{6}$")
