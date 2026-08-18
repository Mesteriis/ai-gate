package com.aigate.router.data.model

import kotlinx.serialization.Serializable

/**
 * 模型能力标签 — 9大维度
 * 按行业共识定义：tool_call / vision / thinking / audio_in / audio_out / video / image_gen / embeddings / realtime
 */
@Serializable
data class ModelCapabilities(
    val toolCall: Boolean = false,
    val vision: Boolean = false,
    val thinking: Boolean = false,
    val audioIn: Boolean = false,
    val audioOut: Boolean = false,
    val video: Boolean = false,
    val imageGen: Boolean = false,
    val embeddings: Boolean = false,
    val realtime: Boolean = false,
) {
    /** 转成 UI 标签列表，按固定顺序 */
    fun toTags(): List<CapabilityTag> {
        val list = mutableListOf<CapabilityTag>()
        if (toolCall)   list += CapabilityTag("tool_call",  "Инструменты",  "🔧")
        if (vision)     list += CapabilityTag("vision",     "Зрение",  "👁️")
        if (thinking)   list += CapabilityTag("thinking",   "Рассуждение",  "🧠")
        if (audioIn)    list += CapabilityTag("audio_in",   "Голос вход",  "🎤")
        if (audioOut)   list += CapabilityTag("audio_out",  "Голос выход",  "🔊")
        if (video)      list += CapabilityTag("video",      "Видео",      "🎬")
        if (imageGen)   list += CapabilityTag("image_gen",  "Генерация изображений",  "🎨")
        if (embeddings) list += CapabilityTag("embeddings", "Эмбеддинги",  "📐")
        if (realtime)   list += CapabilityTag("realtime",   "Реалтайм",  "⚡")
        return list
    }

    companion object {
        /** 从字符串列表快速构建 */
        fun fromKeys(keys: List<String>): ModelCapabilities = ModelCapabilities(
            toolCall   = "tool_call"   in keys,
            vision     = "vision"      in keys,
            thinking   = "thinking"    in keys,
            audioIn    = "audio_in"    in keys,
            audioOut   = "audio_out"   in keys,
            video      = "video"       in keys,
            imageGen   = "image_gen"   in keys,
            embeddings = "embeddings"  in keys,
            realtime   = "realtime"   in keys,
        )
    }
}

@Serializable
data class CapabilityTag(val key: String, val label: String, val icon: String)