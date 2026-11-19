package de.tieph0.ytmsync

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Pink = Color(0xFFFF2D55)
val Violet = Color(0xFF7B2FF7)
val AccentBrush = Brush.linearGradient(listOf(Pink, Violet))

private val Scheme = darkColorScheme(
    primary = Color(0xFFFF5C80),
    onPrimary = Color.White,
    secondary = Color(0xFFB18CFF),
    onSecondary = Color.Black,
    tertiary = Color(0xFF5EE6A8),
    background = Color(0xFF0F0D13),
    onBackground = Color(0xFFECE6F0),
    surface = Color(0xFF0F0D13),
    onSurface = Color(0xFFECE6F0),
    surfaceVariant = Color(0xFF221D2A),
    onSurfaceVariant = Color(0xFFB9B1C4),
    surfaceContainer = Color(0xFF1A1622),
    surfaceContainerHigh = Color(0xFF221D2A),
    outline = Color(0xFF4A4255),
    error = Color(0xFFFF6B6B),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
