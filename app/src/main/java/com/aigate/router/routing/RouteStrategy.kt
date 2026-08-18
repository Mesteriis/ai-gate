package com.aigate.router.routing

/**
 * Именованные стратегии маршрутизации `auto`-запроса. Честные измерения, без «магии
 * качества»:
 *  - FAST    — минимальный TTFT среди healthy (существующая smartSort по скорости).
 *  - CHEAP   — минимальная подтверждённая цена (по pricing-метаданным).
 *  - QUALITY — пользовательский приоритет (порядок провайдеров), не выдуманный рейтинг.
 *  - OFFLINE — только локальные модели (Ollama/loopback).
 *  - QUOTA   — оптимизация подписочных квот: сначала пулы с остатком и запасом до сброса.
 *  - AUTO    — общая политика (сейчас = FAST).
 */
enum class RouteStrategy {
    AUTO, FAST, CHEAP, QUALITY, OFFLINE, QUOTA;

    companion object {
        const val CONFIG_KEY = "route_strategy"

        fun fromName(name: String?): RouteStrategy =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTO
    }
}
