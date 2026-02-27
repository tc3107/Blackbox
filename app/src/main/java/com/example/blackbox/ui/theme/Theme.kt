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
        colorScheme = blackboxColorScheme(),
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
private fun blackboxColorScheme(): ColorScheme {
    val context = LocalContext.current
    val dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val baseScheme = if (dynamicColorAvailable) {
        dynamicDarkColorScheme(context)
    } else {
        darkColorScheme(
            primary = FallbackAccentGrayWhite,
            secondary = FallbackAccentGrayWhite.copy(alpha = 0.84f),
            tertiary = FallbackAccentGrayWhite.copy(alpha = 0.68f)
        )
    }

    val accentColor = if (dynamicColorAvailable) {
        baseScheme.primary
    } else {
        FallbackAccentGrayWhite
    }

    return baseScheme.copy(
        primary = accentColor,
        onPrimary = colorForForeground(accentColor),
        primaryContainer = lerp(NeoSurface, accentColor, 0.22f),
        onPrimaryContainer = Color.White,
        secondary = accentColor.copy(alpha = 0.84f),
        tertiary = accentColor.copy(alpha = 0.68f),
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
private val FallbackAccentGrayWhite = Color(0xFFE3E3E3)
