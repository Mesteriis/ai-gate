package com.aigate.router.service

import com.aigate.router.GatewayApplication
import com.aigate.router.security.CryptoBox
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * API Key 管理器 — 管理访问密钥及其权限
 * 本地请求（localhost/127.0.0.1）默认免密钥
 * 每把密钥可单独控制：可用模型、auto 访问权限
 */
@Serializable
data class ApiKeyEntry(
    val key: String,                    // 密钥字符串
    val label: String = "",             // 标签/备注
    val enabled: Boolean = true,        // 是否启用
    val allowedModels: List<String> = emptyList(),  // 允许访问的模型ID列表（空=全部）
    val autoAccess: Boolean = true,   // 是否允许访问 auto
    val createdAt: Long = System.currentTimeMillis()
)

object KeyManager {
    private const val PREF_KEY = "api_key_entries"
    private val json = Json { ignoreUnknownKeys = true }
    
    /** 获取所有密钥（Keystore 解密；兼容旧的明文存储） */
    fun getAllKeys(): List<ApiKeyEntry> {
        val raw = GatewayForegroundService.getGatewayConfig(PREF_KEY, "")
        if (raw.isBlank()) return emptyList()
        // Ciphertext (base64) → decrypt; legacy plaintext JSON → use as-is.
        val str = CryptoBox.decrypt(raw).ifEmpty { raw }
        return try {
            json.decodeFromString<List<ApiKeyEntry>>(str)
        } catch (_: Exception) { emptyList() }
    }

    /** 保存密钥列表（Keystore 加密） */
    private fun saveAllKeys(keys: List<ApiKeyEntry>) {
        GatewayForegroundService.saveGatewayConfig(PREF_KEY, CryptoBox.encrypt(json.encodeToString(keys)))
    }
    
    /** 添加密钥 */
    fun addKey(key: String, label: String = "", allowedModels: List<String> = emptyList(), autoAccess: Boolean = true): Boolean {
        val keys = getAllKeys().toMutableList()
        if (keys.any { it.key == key }) return false // 已存在
        keys.add(ApiKeyEntry(key = key, label = label, allowedModels = allowedModels, autoAccess = autoAccess))
        saveAllKeys(keys)
        return true
    }

    /** 删除密钥 */
    fun deleteKey(key: String): Boolean {
        val keys = getAllKeys().toMutableList()
        val removed = keys.removeAll { it.key == key }
        if (removed) {
            saveAllKeys(keys)
        }
        return removed
    }
    
    /** 更新密钥 */
    fun updateKey(key: String, label: String? = null, enabled: Boolean? = null, allowedModels: List<String>? = null, autoAccess: Boolean? = null): Boolean {
        val keys = getAllKeys().toMutableList()
        val idx = keys.indexOfFirst { it.key == key }
        if (idx < 0) return false
        val old = keys[idx]
        keys[idx] = old.copy(
            label = label ?: old.label,
            enabled = enabled ?: old.enabled,
            allowedModels = allowedModels ?: old.allowedModels,
            autoAccess = autoAccess ?: old.autoAccess
        )
        saveAllKeys(keys)
        return true
    }

    /** 验证密钥是否有效 */
    fun validateKey(key: String): ApiKeyEntry? {
        return getAllKeys().find { it.key == key && it.enabled }
    }
    
    /** 检查密钥是否有权访问模型 */
    fun canAccessModel(key: String, modelId: String): Boolean {
        val entry = validateKey(key) ?: return false
        if (entry.allowedModels.isEmpty()) return true // 空=全部
        return entry.allowedModels.contains(modelId)
    }
    
    /** 检查密钥是否有权访问 auto */
    fun canAccessAuto(key: String): Boolean {
        return validateKey(key)?.autoAccess ?: false
    }
    
    /** 清空所有密钥 */
    fun clearAllKeys() {
        saveAllKeys(emptyList())
    }

    /**
     * Только настоящий loopback освобождается от авторизации.
     * ВАЖНО: вся частная сеть (RFC1918) больше НЕ обходит проверку — доступ из LAN
     * возможен лишь в LAN-режиме и только по токену.
     */
    fun isLoopback(ip: String): Boolean {
        return ip == "localhost" || ip == "::1" || ip.startsWith("127.")
    }
}