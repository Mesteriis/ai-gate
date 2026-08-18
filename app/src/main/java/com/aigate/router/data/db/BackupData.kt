package com.aigate.router.data.db

import com.aigate.router.data.model.AiModel
import com.aigate.router.data.model.Provider
import com.aigate.router.data.model.TokenUsage
import com.aigate.router.data.model.SpeedHistory
import com.aigate.router.data.model.RoutingRule
import kotlinx.serialization.Serializable

/**
 * 备份数据的顶层结构，包含数据库中所有的表数据
 * 序列化为 JSON 文件供导出/导入使用
 */
@Serializable
data class BackupData(
    val version: Int = 5,
    val timestamp: Long = System.currentTimeMillis(),
    val providers: List<Provider> = emptyList(),
    val models: List<AiModel> = emptyList(),
    val tokenUsage: List<TokenUsage> = emptyList(),
    val speedHistory: List<SpeedHistory> = emptyList(),   // ★ v5: 测速历史
    val routingRules: List<RoutingRule> = emptyList(),    // ★ v5: 路由规则
    val proxyListJson: String = "",           // ★ 代理列表配置
    val gatewayPort: Int = 8889,               // ★ 网关端口
    val apiKeyEntriesJson: String = "",         // ★ 密钥列表
    val settingsJson: String = ""               // ★ 所有设置
)
