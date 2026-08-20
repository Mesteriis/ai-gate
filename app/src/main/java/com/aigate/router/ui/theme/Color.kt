package com.aigate.router.ui.theme

import androidx.compose.ui.graphics.Color

// ── AiGate / AiGate — «морозные врата» (frost-gate) палитра ──
// Светлая морозно-голубая эстетика: белые карточки, азурные акценты, ледяное сияние.

// Акценты
val Primary = Color(0xFF2E6FE0)         // азур — главный акцент
val PrimaryVariant = Color(0xFF1E50B5)
val Secondary = Color(0xFF56B6E8)       // ледяной циан (сияние врат)
val SecondaryVariant = Color(0xFF3A93C9)

// Светлая тема (морозная)
val LightBackground = Color(0xFFEDF3FC)      // бледный морозно-голубой фон
val LightSurface = Color(0xFFFFFFFF)         // карточки — белые
val LightSurfaceVariant = Color(0xFFE4EDFA)  // мягкие голубоватые панели

// Тёмная тема (морозно-тёмная)
val DarkBackground = Color(0xFF0E1726)
val DarkSurface = Color(0xFF16233A)
val DarkSurfaceVariant = Color(0xFF213152)

// Функциональные
val Success = Color(0xFF23B26A)
val Warning = Color(0xFFF0A020)
val Error = Color(0xFFE5484D)
val Info = Color(0xFF3B82F6)

// Статусные
val Online = Color(0xFF23B26A)
val Offline = Color(0xFF94A3B8)
val Pending = Color(0xFFF0A020)

// Дополнительные оттенки для градиента «врат»
val FrostTop = Color(0xFFDCE9FB)
val FrostGlow = Color(0xFF7FC4F5)

// Текст и штрихи светлой темы
val OnLight = Color(0xFF16233A)          // чернильный сине-серый — основной текст
val OnLightMuted = Color(0xFF4A5A70)     // вторичный текст
val LightOutline = Color(0xFFB9CBE6)
val LightOutlineVariant = Color(0xFFDBE6F5)  // дивайдеры

// Контейнеры светлой темы
val LightSecondaryContainer = Color(0xFFDFEAFC)
val LightErrorContainer = Color(0xFFFBE0E1)
val LightOnErrorContainer = Color(0xFF8F2327)

// Тональные поверхности светлой темы
val LightSurfaceContainerLow = Color(0xFFF4F8FE)
val LightSurfaceContainer = Color(0xFFEEF4FD)
val LightSurfaceDim = Color(0xFFDCE6F5)
val LightInversePrimary = Color(0xFF7FB3F6)  // азур, осветлённый для инверсных поверхностей

// Текст и штрихи тёмной темы
val DarkOnAccent = Color(0xFF06121F)     // почти чёрный — текст на ярких акцентах
val DarkOnSurface = Color(0xFFE6EEF9)
val DarkOnSurfaceMuted = Color(0xFFA9BAD4)
val DarkOutline = Color(0xFF3A4E75)
val DarkOutlineVariant = Color(0xFF2A3B5C)

// Контейнеры тёмной темы
val DarkAccentContainer = Color(0xFF1D3560)      // общий для primary- и secondary-контейнеров
val DarkOnAccentContainer = Color(0xFFC2D7FB)
val DarkTertiaryContainer = Color(0xFF14304F)
val DarkOnTertiaryContainer = Color(0xFFC9E5FA)
val DarkError = Color(0xFFF06A6F)                // мягче светлого Error, чтобы не «жёг» на тёмном
val DarkErrorContainer = Color(0xFF54191C)
val DarkOnErrorContainer = Color(0xFFF5B4B7)

// Тональные поверхности тёмной темы
val DarkSurfaceContainerLowest = Color(0xFF0B1320)
val DarkSurfaceContainerLow = Color(0xFF1A2A45)
val DarkSurfaceContainer = Color(0xFF1E2F4E)
val DarkSurfaceContainerHigh = Color(0xFF24355C)
val DarkSurfaceContainerHighest = Color(0xFF2A3E68)
