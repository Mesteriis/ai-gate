package com.aigate.router.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val OnLight = Color(0xFF16233A)
private val OnLightMuted = Color(0xFF4A5A70)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = FrostTop,
    onPrimaryContainer = PrimaryVariant,
    secondary = Secondary,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = OnLight,
    surface = LightSurface,
    onSurface = OnLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = OnLightMuted,
    error = Error,
    onError = Color.White,
    outline = Color(0xFFB9CBE6)
)

private val DarkColorScheme = darkColorScheme(
    primary = Secondary,
    onPrimary = Color(0xFF06121F),
    secondary = Secondary,
    background = DarkBackground,
    onBackground = Color(0xFFE6EEF9),
    surface = DarkSurface,
    onSurface = Color(0xFFE6EEF9),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFA9BAD4),
    error = Error
)

/**
 * ИИ Врата theme — frost-gate look. Defaults to the light frost palette (matching the
 * product reference); dark frost is available when [darkTheme] is passed true. Material You
 * dynamic color is intentionally OFF so the brand palette is always used.
 */
@Composable
fun GatewayTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
