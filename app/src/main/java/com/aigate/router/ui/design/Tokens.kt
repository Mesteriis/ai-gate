package com.aigate.router.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Расширение frost-gate палитры поверх Material3 ColorScheme: семантические
 * контейнеры (успех/предупреждение/инфо), шкала давления квот и dataviz-токены.
 * Единственный источник цвета вне MaterialTheme.colorScheme — экраны не должны
 * создавать Color(0x...) или magic-alpha локально.
 */
@Immutable
data class GatewayColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    // Шкала давления ресурса (quota/ResourceTypes.ResourcePressure)
    val pressureFree: Color,
    val pressureNormal: Color,
    val pressureConserve: Color,
    val pressureCritical: Color,
    val pressureUnknown: Color,
    // Статусы сущностей
    val online: Color,
    val offline: Color,
    val pending: Color,
    // Dataviz
    // Серия прошла валидатор различимости (CVD ΔE≥15, контраст ≥3:1 в обеих
    // темах); порядок фиксированный, цвет закреплён за сущностью.
    // Пара [0]+[1] — «входные/выходные».
    val chartSeries: List<Color>,
    val chartGrid: Color,
    val chartAxisLabel: Color,
    val chartProjection: Color,
    val chartSelected: Color,
    // Премиум-поверхности: карточки и hero-блок
    val cardBorder: Color,
    val cardShadow: Color,
    val heroGradient: List<Color>,
    // Сияние врат — используется с малой альфой при отрисовке градиента.
    val heroGlow: Color,
    // Тональные поверхности (замена альфа-хаков)
    val surfaceContainerLow: Color,
    val surfaceContainerHigh: Color,
)

val LightGatewayColors = GatewayColors(
    success = Color(0xFF23B26A),
    onSuccess = Color.White,
    successContainer = Color(0xFFDCF5E7),
    onSuccessContainer = Color(0xFF115B36),
    warning = Color(0xFFF0A020),
    onWarning = Color.White,
    warningContainer = Color(0xFFFDF0D8),
    onWarningContainer = Color(0xFF7A5510),
    info = Color(0xFF3B82F6),
    infoContainer = Color(0xFFDFEAFC),
    onInfoContainer = Color(0xFF1E50B5),
    errorContainer = Color(0xFFFBE0E1),
    onErrorContainer = Color(0xFF8F2327),
    pressureFree = Color(0xFF23B26A),
    pressureNormal = Color(0xFF2E6FE0),
    pressureConserve = Color(0xFFF0A020),
    pressureCritical = Color(0xFFE5484D),
    pressureUnknown = Color(0xFF94A3B8),
    online = Color(0xFF23B26A),
    offline = Color(0xFF94A3B8),
    pending = Color(0xFFF0A020),
    chartSeries = listOf(
        Color(0xFF2E6FE0), // азур
        Color(0xFFC07E12), // охра
        Color(0xFF8F62DE), // фиолет
        Color(0xFF1D9A5E), // зелёный
        Color(0xFF2492C8), // стальной циан
        Color(0xFFD25A68), // коралл
    ),
    chartGrid = Color(0xFFDBE6F5),
    chartAxisLabel = Color(0xFF7E90A8),
    chartProjection = Color(0xFF9DB8DF),
    chartSelected = Color(0xFF1E50B5),
    cardBorder = Color(0x1A2E6FE0),
    cardShadow = Color(0x332E6FE0),
    heroGradient = listOf(Color.White, Color(0xFFE7F0FC)),
    heroGlow = Color(0xFF7FC4F5),
    surfaceContainerLow = Color(0xFFF4F8FE),
    surfaceContainerHigh = Color(0xFFE4EDFA),
)

val DarkGatewayColors = GatewayColors(
    success = Color(0xFF3BCB82),
    onSuccess = Color(0xFF06121F),
    successContainer = Color(0xFF14402B),
    onSuccessContainer = Color(0xFFA4E8C6),
    warning = Color(0xFFF3B04E),
    onWarning = Color(0xFF06121F),
    warningContainer = Color(0xFF4A3712),
    onWarningContainer = Color(0xFFF8DCA8),
    info = Color(0xFF6AA2F8),
    infoContainer = Color(0xFF1D3560),
    onInfoContainer = Color(0xFFC2D7FB),
    errorContainer = Color(0xFF54191C),
    onErrorContainer = Color(0xFFF5B4B7),
    pressureFree = Color(0xFF3BCB82),
    pressureNormal = Color(0xFF6AA2F8),
    pressureConserve = Color(0xFFF3B04E),
    pressureCritical = Color(0xFFF06A6F),
    pressureUnknown = Color(0xFF64748B),
    online = Color(0xFF3BCB82),
    offline = Color(0xFF64748B),
    pending = Color(0xFFF3B04E),
    chartSeries = listOf(
        Color(0xFF4A85EC), // азур
        Color(0xFFC67F14), // охра
        Color(0xFF9A6FE0), // фиолет
        Color(0xFF2AA866), // зелёный
        Color(0xFF2E9FD0), // стальной циан
        Color(0xFFD9636F), // коралл
    ),
    chartGrid = Color(0xFF2A3B5C),
    chartAxisLabel = Color(0xFF8FA3C0),
    chartProjection = Color(0xFF4A648F),
    chartSelected = Color(0xFF7FB3F6),
    cardBorder = Color(0x247FC4F5),
    cardShadow = Color(0x99000000),
    heroGradient = listOf(Color(0xFF16233A), Color(0xFF1B3055)),
    heroGlow = Color(0xFF7FC4F5),
    surfaceContainerLow = Color(0xFF1A2A45),
    surfaceContainerHigh = Color(0xFF24355C),
)

val LocalGatewayColors = staticCompositionLocalOf { LightGatewayColors }

/** Шкала отступов — вместо разнобоя 2/4/6/8/12/16/20 по экранам. */
@Immutable
data class GatewaySpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    /** Максимальная ширина контента на широких экранах (Fold/планшет). */
    val contentMaxWidth: Dp = 720.dp,
)

val LocalGatewaySpacing = staticCompositionLocalOf { GatewaySpacing() }

/** Доступ к расширенным токенам: `Gateway.colors`, `Gateway.spacing`, `Gateway.motion`. */
object Gateway {
    val colors: GatewayColors
        @Composable @ReadOnlyComposable get() = LocalGatewayColors.current
    val spacing: GatewaySpacing
        @Composable @ReadOnlyComposable get() = LocalGatewaySpacing.current
    val motion: GatewayMotion
        @Composable @ReadOnlyComposable get() = LocalGatewayMotion.current
}

@Composable
fun ProvideGatewayDesign(darkTheme: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalGatewayColors provides if (darkTheme) DarkGatewayColors else LightGatewayColors,
        LocalGatewaySpacing provides GatewaySpacing(),
        LocalGatewayMotion provides GatewayMotion(),
        content = content,
    )
}
