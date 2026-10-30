package com.obdfree.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF071018)
val DeepInk = Color(0xFF03080D)
val Glass = Color(0x1FFFFFFF)
val GlassStrong = Color(0x2BFFFFFF)
val GlassBorder = Color(0x35FFFFFF)
val Electric = Color(0xFF7DE7FF)
val ElectricStrong = Color(0xFF33CFF5)
val Mint = Color(0xFF78F3C7)
val Amber = Color(0xFFFFCB74)
val Danger = Color(0xFFFF7C8E)
val TextPrimary = Color(0xFFF4F8FB)
val TextSecondary = Color(0xFF9BAFBD)

private val ObdColors = darkColorScheme(
    primary = Electric,
    onPrimary = DeepInk,
    secondary = Mint,
    tertiary = Amber,
    error = Danger,
    background = DeepInk,
    onBackground = TextPrimary,
    surface = Ink,
    onSurface = TextPrimary,
    surfaceVariant = GlassStrong,
    onSurfaceVariant = TextSecondary,
)

@Composable
fun ObdTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ObdColors,
        content = content,
    )
}
