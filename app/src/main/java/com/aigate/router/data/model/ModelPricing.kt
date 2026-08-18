package com.aigate.router.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Прайс модели: цена за 1M входных/выходных токенов. Используется для расчёта
 * оценочной стоимости (estimated cost) — это НЕ баланс провайдера.
 *
 * Каждая запись помечена источником и временем кэширования, потому что цены дрейфуют:
 *  - source = "bundled" — встроенная таблица AiGate (может устареть).
 *  - source = "user"    — задано пользователем вручную (приоритетнее bundled).
 *
 * Ключ уникальности — (providerType, modelId). providerType — это `Provider.type`
 * (openai/anthropic/gemini/ollama/custom), чтобы одна запись покрывала всех
 * провайдеров одного типа.
 */
@Entity(
    tableName = "model_pricing",
    indices = [Index(value = ["provider_type", "model_id"], unique = true)]
)
@Serializable
data class ModelPricing(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "provider_type")
    val providerType: String,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    /** Цена за 1M входных токенов в валюте `currency`. */
    @ColumnInfo(name = "input_per_1m")
    val inputPer1M: Double,
    /** Цена за 1M выходных токенов. */
    @ColumnInfo(name = "output_per_1m")
    val outputPer1M: Double,
    @ColumnInfo(name = "currency")
    val currency: String = "USD",
    /** "bundled" | "user". */
    @ColumnInfo(name = "source")
    val source: String = "user",
    @ColumnInfo(name = "cached_at")
    val cachedAt: Long = System.currentTimeMillis()
)
