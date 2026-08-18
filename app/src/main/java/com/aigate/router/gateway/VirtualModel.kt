package com.aigate.router.gateway

/**
 * 虚拟「自动」模型 —— 网关自动挑选最快的可用真实模型进行转发。
 *
 * The virtual "auto" model: when a client requests it, the gateway picks the
 * fastest healthy real model and forwards there (with failover). The legacy id
 * "qtai-sj" is accepted as an alias so existing client configs keep working;
 * the gateway always reports the model back to clients as [ID].
 */
object VirtualModel {
    /** Canonical id the gateway emits to clients. */
    const val ID = "auto"

    /** All ids that resolve to the virtual auto-router (canonical + legacy aliases). */
    val ALIASES = setOf("auto", "aigate-auto", "qtai-sj")

    /** True if [modelId] refers to the virtual auto-router. */
    fun isVirtual(modelId: String?): Boolean = modelId != null && modelId in ALIASES
}
