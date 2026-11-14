package com.example.dueo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8A5FF), onPrimary = Color(0xFF24124F),
    background = Color(0xFF0A0B16), surface = Color(0xFF191A26),
    surfaceVariant = Color(0xFF282936), onBackground = Color(0xFFF4F1FF),
    onSurface = Color(0xFFF4F1FF), onSurfaceVariant = Color(0xFFB6B7CA)
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF7046D9), onPrimary = Color.White,
    background = Color(0xFFF6F7FB), surface = Color.White,
    surfaceVariant = Color(0xFFEEF0F7), onBackground = Color(0xFF202039),
    onSurface = Color(0xFF202039), onSurfaceVariant = Color(0xFF727187)
)
@Composable
fun DueoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val target = if (darkTheme) DarkColors else LightColors
    @Composable fun blend(color: Color): Color { val value by animateColorAsState(color, tween(480), label = "theme palette"); return value }
    val colors = target.copy(primary = blend(target.primary), onPrimary = blend(target.onPrimary),
        background = blend(target.background), surface = blend(target.surface), surfaceVariant = blend(target.surfaceVariant),
        onBackground = blend(target.onBackground), onSurface = blend(target.onSurface), onSurfaceVariant = blend(target.onSurfaceVariant))
    MaterialTheme(colorScheme = colors, typography = Typography, shapes = DueoDesign.Shapes, content = content)
}
