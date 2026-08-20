package com.aigate.router.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Единая моторика интерфейса: длительности, кривые и готовые модификаторы.
 * Без неё каждый экран заводил свои tween(300) и «дорогое» ощущение
 * рассыпалось — движение должно читаться как один материал.
 */
@Immutable
data class GatewayMotion(
    /** Микроотклик: нажатие, переключение состояния чипа. */
    val fast: Int = 140,
    /** Основная длительность: появление карточки, смена значения. */
    val normal: Int = 260,
    /** Крупный жест: разворот графика, вход экрана. */
    val slow: Int = 460,
    /** Шаг задержки между соседними карточками при входе. */
    val stagger: Int = 55,
    /** Выразительная кривая: быстрый разгон, мягкое торможение. */
    val emphasized: Easing = FastOutSlowInEasing,
    /** Кривая появления: элемент «прилетает» и замирает без отката. */
    val entering: Easing = LinearOutSlowInEasing,
)

/** Доступ к моторике: `Gateway.motion`. */
val LocalGatewayMotion = androidx.compose.runtime.staticCompositionLocalOf { GatewayMotion() }

/**
 * Параллакс для витринной карточки: при скролле она уходит вверх БЫСТРЕЕ
 * контента, слегка сжимается и растворяется. Глубина сцены — то, что отличает
 * «дорогой» дашборд от плоского списка карточек.
 *
 * Направление именно такое: если витрину, наоборот, притормозить, она отстанет
 * от списка и будет просвечивать из-под соседних карточек — на экране это
 * читается как грязь, а не как глубина.
 *
 * [factor] — насколько быстрее контента уходит карточка (0.3 значит «на треть»),
 * [fadeDistance] — путь скролла, на котором карточка полностью растворяется,
 * [scaleTo] — минимальный масштаб к концу пути.
 */
fun Modifier.parallax(
    scroll: ScrollState,
    factor: Float = 0.3f,
    fadeDistance: Dp = 220.dp,
    scaleTo: Float = 0.94f,
): Modifier = composed {
    val fadePx = with(androidx.compose.ui.platform.LocalDensity.current) { fadeDistance.toPx() }
    graphicsLayer {
        val offset = scroll.value.toFloat()
        translationY = -offset * factor
        val progress = (offset / fadePx).coerceIn(0f, 1f)
        alpha = 1f - progress
        val scale = 1f - (1f - scaleTo) * progress
        scaleX = scale
        scaleY = scale
        // Уменьшение к верхнему краю: карточка «уходит в глубину», а не
        // съезжает к центру собственной рамки.
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
    }
}

/**
 * Ступенчатое появление: карточка всплывает снизу с задержкой по [index],
 * поэтому экран собирается волной, а не мигает целиком.
 */
fun Modifier.appear(index: Int = 0, offsetY: Dp = 14.dp): Modifier = composed {
    val motion = Gateway.motion
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(
            durationMillis = motion.normal,
            delayMillis = index * motion.stagger,
            easing = motion.entering,
        ),
        label = "appear",
    )
    val shiftPx = with(androidx.compose.ui.platform.LocalDensity.current) { offsetY.toPx() }
    graphicsLayer {
        alpha = progress
        translationY = shiftPx * (1f - progress)
    }
}

/**
 * Отклик на нажатие: карточка едва вдавливается. Осязаемость даёт ощущение
 * материала — но амплитуда намеренно крошечная, чтобы список не «прыгал».
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.985f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(Gateway.motion.fast, easing = Gateway.motion.emphasized),
        label = "press",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Прогресс разворачивания графика 0→1. Графики рисуются на Canvas, поэтому
 * анимировать нужно само значение, а не композицию: бары растут от базовой
 * линии, линия вычерчивается слева направо.
 *
 * [key] — при смене данных (период, метрика) анимация проигрывается заново.
 */
@Composable
fun rememberChartReveal(key: Any? = Unit): Float {
    val motion = Gateway.motion
    // Именно Animatable, а не animateFloatAsState: тот сохраняет текущее
    // значение между ключами и при смене периода график не перевычерчивался бы,
    // а мгновенно перескакивал на новые данные.
    val anim = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        anim.animateTo(1f, tween(motion.slow, easing = motion.emphasized))
    }
    return anim.value
}

/**
 * Плавный переход числа: значение метрики не подменяется рывком, а доезжает.
 * Возвращает анимированное значение — форматирование остаётся за вызывающим.
 *
 * Считает во Float, поэтому целые выше ~16 млн представимы неточно. Для счётчиков
 * байт это безопасно (погрешность прячется за «МБ»/«ГБ»), но для значений,
 * которые показываются до единиц, анимировать число нельзя.
 */
@Composable
fun animatedValue(target: Float, key: Any? = Unit): Float {
    val value by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(Gateway.motion.normal, easing = Gateway.motion.emphasized),
        label = "value-$key",
    )
    return value
}

/**
 * Мягкая кромка у прокручиваемого блока: содержимое растворяется у края,
 * вместо того чтобы обрубаться посреди строки.
 */
fun Modifier.fadingEdge(height: Dp = 18.dp, atTop: Boolean = true): Modifier = composed {
    val hPx = with(androidx.compose.ui.platform.LocalDensity.current) { height.toPx() }
    drawWithContent {
        drawContent()
        val brush = androidx.compose.ui.graphics.Brush.verticalGradient(
            colors = listOf(
                androidx.compose.ui.graphics.Color.Transparent,
                androidx.compose.ui.graphics.Color.Black,
            ),
            startY = if (atTop) 0f else size.height,
            endY = if (atTop) hPx else size.height - hPx,
        )
        drawRect(brush = brush, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
    }
}

/** Насколько блок «утоплен» скроллом — для связки шапки и контента. */
fun scrollProgress(scroll: ScrollState, distancePx: Float): Float =
    if (distancePx <= 0f) 0f else (abs(scroll.value) / distancePx).coerceIn(0f, 1f)
