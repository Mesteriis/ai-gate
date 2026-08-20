package com.aigate.router.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Обе схемы заданы полностью, чтобы ни одно поле не проваливалось
// в лиловый M3 baseline (индикаторы NavigationBar, чипы, дивайдеры и т.п.).
private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = FrostTop,
    onPrimaryContainer = PrimaryVariant,
    inversePrimary = LightInversePrimary,
    secondary = Secondary,
    onSecondary = Color.White,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = PrimaryVariant,
    tertiary = SecondaryVariant,
    onTertiary = Color.White,
    tertiaryContainer = FrostTop,
    onTertiaryContainer = PrimaryVariant,
    background = LightBackground,
    onBackground = OnLight,
    surface = LightSurface,
    onSurface = OnLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = OnLightMuted,
    error = Error,
    onError = Color.White,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    // Инверсные поверхности зеркалят тёмную тему.
    inverseSurface = DarkSurface,
    inverseOnSurface = DarkOnSurface,
    surfaceBright = LightSurface,
    surfaceDim = LightSurfaceDim,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceVariant,
    surfaceContainerHighest = FrostTop,
)

private val DarkColorScheme = darkColorScheme(
    primary = Secondary,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkAccentContainer,
    onPrimaryContainer = DarkOnAccentContainer,
    inversePrimary = Primary,
    secondary = Secondary,
    onSecondary = DarkOnAccent,
    secondaryContainer = DarkAccentContainer,
    onSecondaryContainer = DarkOnAccentContainer,
    tertiary = FrostGlow,
    onTertiary = DarkOnAccent,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceMuted,
    error = DarkError,
    onError = DarkOnAccent,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    // Инверсные поверхности зеркалят светлую тему.
    inverseSurface = DarkOnSurface,
    inverseOnSurface = DarkSurface,
    surfaceBright = DarkSurfaceContainerHigh,
    surfaceDim = DarkBackground,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
)

/**
 * AiGate theme — frost-gate look. По умолчанию следует системной теме
 * ([isSystemInDarkTheme]); светлая и тёмная морозные палитры собраны целиком
 * из брендовых токенов. Material You dynamic color намеренно ВЫКЛЮЧЕН,
 * чтобы всегда использовалась брендовая палитра.
 */
@Composable
fun GatewayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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
        typography = Typography
    ) {
        com.aigate.router.ui.design.ProvideGatewayDesign(darkTheme = darkTheme, content = content)
    }
}
