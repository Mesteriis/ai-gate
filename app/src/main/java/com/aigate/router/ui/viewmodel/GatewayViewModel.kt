package com.aigate.router.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.app.ActivityManager
import android.content.Context
import com.aigate.router.GatewayApplication
import com.aigate.router.catalog.ModelCatalogRepository
import com.aigate.router.data.credential.CredentialStore
import com.aigate.router.data.model.LocalModel
import com.aigate.router.download.LocalModelsRepository
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.data.model.AiModel
import com.aigate.router.data.model.ModelRouteKey
import com.aigate.router.data.model.findByRouteKey
import com.aigate.router.data.model.routeKey
import com.aigate.router.data.model.Provider
import com.aigate.router.data.model.TokenUsage
import com.aigate.router.data.model.ApiKeyUsageRow
import com.aigate.router.data.db.BackupManager
import com.aigate.router.network.Socks5SocketFactory
import com.aigate.router.network.UpstreamClient
import com.aigate.router.service.GatewayForegroundService
import com.aigate.router.service.LiveSession
import com.aigate.router.gateway.GatewayService
import com.aigate.router.gateway.ModelCapabilityManager
import com.aigate.router.gateway.VirtualModel
import com.aigate.router.data.model.SpeedHistory
import com.aigate.router.data.db.SpeedHistoryDao
import com.aigate.router.usage.UsageStats
import com.aigate.router.utils.localizeGeneratedName
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

// 三指标测速
import com.aigate.router.utils.ModelSpeedTester
import com.aigate.router.data.model.SpeedMetrics
import com.aigate.router.data.model.ModelCapabilities
import com.aigate.router.data.model.CapabilityTag

/**
 * 网关应用的主 ViewModel —— 管理全部业务状态
 * 包括：服务商管理、模型同步、聊天对话、会话管理、Token用量统计
 */

// ==================== 包级全局变量（Activity重建不丢失）====================
/** 流水线测速状态条目 */
/**
 * Исход замера. Раньше он жил эмодзи-префиксом внутри [PipelineTestItem.status]
 * и разбирался обратно в UI — данные несли оформление, а экран угадывал смысл.
 */
@kotlinx.serialization.Serializable
enum class TestOutcome { Pending, Success, Failure }

@kotlinx.serialization.Serializable
data class PipelineTestItem(
    val modelId: String,
    val modelName: String,
    val providerId: Long = 0L,
    val status: String,
    val outcome: TestOutcome = TestOutcome.Pending,
    val latencyMs: Long = 0,
    val isCurrent: Boolean = false
) {
    val selectionKey: String
        get() = ModelRouteKey.encode(providerId, modelId)
}

private val _pipelineStatus = MutableStateFlow<List<PipelineTestItem>>(emptyList())
val pipelineStatus: StateFlow<List<PipelineTestItem>> = _pipelineStatus.asStateFlow()

/** ★★ 流水线测速进度 ★★ */
private val _pipelineProgress = MutableStateFlow(0f)
val pipelineProgress: StateFlow<Float> = _pipelineProgress.asStateFlow()

private val _pipelineRunning = MutableStateFlow(false)
val pipelineRunning: StateFlow<Boolean> = _pipelineRunning.asStateFlow()

/** ★★ 测速倒计时（秒），0表示不在倒计时 ★★ */
private val _pipelineCountdown = MutableStateFlow(0)
val pipelineCountdown: StateFlow<Int> = _pipelineCountdown.asStateFlow()

/** ★★ auto 虚拟模型独立状态 ★★ */
data class AutoModelStatus(
    val available: Boolean = false,
    val bestModelId: String? = null,
    val bestModelName: String? = null,
    val bestTtft: Long = 0,
    val lastUpdated: Long = 0,
    val isTesting: Boolean = false,
)

private val _autoModelStatus = MutableStateFlow(AutoModelStatus())
val autoModelStatus: StateFlow<AutoModelStatus> = _autoModelStatus.asStateFlow()

private var pipelineJob: kotlinx.coroutines.Job? = null

private fun savePipelineCache(items: List<PipelineTestItem>) {
    try {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val str = json.encodeToString(items)
        GatewayForegroundService.saveGatewayConfig("pipeline_cache", str)
    } catch (_: Exception) { }
}

/** ★ 从 SharedPreferences 加载缓存的测速结果 */
private fun loadPipelineCache(): List<PipelineTestItem> {
    return try {
        val str = GatewayForegroundService.getGatewayConfig("pipeline_cache", "")
        if (str.isBlank()) return emptyList()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        json.decodeFromString<List<PipelineTestItem>>(str)
    } catch (_: Exception) { emptyList() }
}

class GatewayViewModel(
    private val database: AppDatabase
) : ViewModel() {

    // ==================== JSON 解析器 ====================
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ==================== 服务商相关 ====================
    val providers: StateFlow<List<Provider>> = database.providerDao()
        .getAllProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ==================== 模型相关 ====================
    /** 所有模型（仅来自已启用服务商） */
    val models: StateFlow<List<AiModel>> = database.aiModelDao()
        .getAllModelsWithEnabledProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 仅返回已启用的模型——供聊天界面和网关API使用 */
    val enabledModels: StateFlow<List<AiModel>> = database.aiModelDao()
        .getEnabledModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ==================== 网关服务状态 ====================
    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    // ==================== 网关端口配置 ====================
    private val _gatewayPort = MutableStateFlow(8889)
    val gatewayPort: StateFlow<Int> = _gatewayPort.asStateFlow()

    // ==================== 代理配置（多代理列表支持）====================

    /**
     * 单个代理配置条目
     */
    @kotlinx.serialization.Serializable
    data class ProxyProfile(
        val id: String = java.util.UUID.randomUUID().toString().take(8),  // 唯一标识
        val name: String = "Новый прокси",                                       // 代理名称
        val type: String = "HTTP",                                         // HTTP / SOCKS5 / VMESS / SS / VLESS
        val host: String = "",
        val port: Int = 1080,
        val username: String = "",
        val password: String = "",
        val enabled: Boolean = false,
        val extraJson: String = ""                                         // 协议扩展参数（vmess/ss/vless的加密、path、security等）
    )

    /** 所有代理列表 */
    private val _proxyProfiles = MutableStateFlow<List<ProxyProfile>>(emptyList())
    val proxyProfiles: StateFlow<List<ProxyProfile>> = _proxyProfiles.asStateFlow()

    /** 当前激活的代理 ID（null 表示无代理） */
    private val _activeProxyId = MutableStateFlow<String?>(null)
    val activeProxyId: StateFlow<String?> = _activeProxyId.asStateFlow()

    /** 代理全局开关（是否启用代理加速） */
    private val _proxyEnabled = MutableStateFlow(false)
    val proxyEnabled: StateFlow<Boolean> = _proxyEnabled.asStateFlow()

    // ==================== 流式输出开关 ====================
    private val _streamEnabled = MutableStateFlow(true)
    val streamEnabled: StateFlow<Boolean> = _streamEnabled.asStateFlow()

    fun setStreamEnabled(enabled: Boolean) {
        _streamEnabled.value = enabled
        GatewayForegroundService.saveGatewayConfig("stream_enabled", enabled.toString())
    }

    // ==================== 当前活跃节点名称（通知栏动态指示灯用） ====================
    @Volatile
    var activeNodeName: String = ""
        private set

    fun setActiveNodeName(name: String) {
        activeNodeName = name
    }
    private val _aboutClickCount = MutableStateFlow(0)
    val aboutClickCount: StateFlow<Int> = _aboutClickCount.asStateFlow()
    private var _lastClickTime = 0L

    // ==================== 对话框/表单状态 ====================
    private val _showAddProviderDialog = MutableStateFlow(false)
    val showAddProviderDialog: StateFlow<Boolean> = _showAddProviderDialog.asStateFlow()

    private val _showEditProviderDialog = MutableStateFlow<Provider?>(null)
    val showEditProviderDialog: StateFlow<Provider?> = _showEditProviderDialog.asStateFlow()

    private val _showProxyConfigDialog = MutableStateFlow(false)
    val showProxyConfigDialog: StateFlow<Boolean> = _showProxyConfigDialog.asStateFlow()

    private val _showEditModelDialog = MutableStateFlow<AiModel?>(null)
    val showEditModelDialog: StateFlow<AiModel?> = _showEditModelDialog.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    // ==================== 模型同步状态 ====================
    private val _syncingProviderId = MutableStateFlow<Long?>(null)
    val syncingProviderId: StateFlow<Long?> = _syncingProviderId.asStateFlow()

    private val _syncResult = MutableStateFlow<String?>(null)
    val syncResult: StateFlow<String?> = _syncResult.asStateFlow()

    /** 选中的模型 */
    private val _selectedModel = MutableStateFlow<AiModel?>(null)
    val selectedModel: StateFlow<AiModel?> = _selectedModel.asStateFlow()

    // ==================== Token 统计 ====================
val allTokenUsage: StateFlow<List<TokenUsage>> = database.tokenUsageDao()
    .getAllUsage()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ==================== Статистика за период ====================
    /** Период статистики в днях — один на все разрезы вкладки, чтобы графики не расходились. */
    val statsPeriodDays = MutableStateFlow(14)

    fun setStatsPeriod(days: Int) {
        statsPeriodDays.value = days
    }

    /**
     * Срез расхода за выбранный период. Агрегация идёт по всем строкам
     * расхода, поэтому пересчёт уведён с главного потока на Default.
     */
    val statsSnapshot: StateFlow<UsageStats.Snapshot?> =
        combine(allTokenUsage, providers, statsPeriodDays) { rows, providerList, days ->
            // «Сейчас» берётся в момент пересчёта: зафиксированная при
            // подписке отметка сдвигала бы границу периода на открытом экране.
            UsageStats.snapshot(
                rows = rows,
                providerNames = providerList.associate { it.id to it.name },
                nowMs = System.currentTimeMillis(),
                days = days,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ==================== 按API密钥统计用量 ====================
    private val _apiKeyUsageRows = MutableStateFlow<List<ApiKeyUsageRow>>(emptyList())
    val apiKeyUsageRows: StateFlow<List<ApiKeyUsageRow>> = _apiKeyUsageRows.asStateFlow()
    fun loadApiKeyUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            _apiKeyUsageRows.value = database.tokenUsageDao().getUsageByApiKey()
        }
    }

    // ==================== 自定义路由规则 ====================
    /** 加载所有路由规则 */
    suspend fun getAllRoutingRules(): List<com.aigate.router.data.model.RoutingRule> =
        database.routingRuleDao().getAllRules().first()

    /** 保存路由规则 */
    fun saveRoutingRule(rule: com.aigate.router.data.model.RoutingRule) {
        viewModelScope.launch(Dispatchers.IO) {
            database.routingRuleDao().insert(rule)
            com.aigate.router.gateway.RoutingRuleManager.invalidateCache()
        }
    }

    /** 更新路由规则 */
    fun updateRoutingRule(rule: com.aigate.router.data.model.RoutingRule) {
        viewModelScope.launch(Dispatchers.IO) {
            database.routingRuleDao().update(rule)
            com.aigate.router.gateway.RoutingRuleManager.invalidateCache()
        }
    }

    /** 删除路由规则 */
    fun deleteRoutingRule(rule: com.aigate.router.data.model.RoutingRule) {
        viewModelScope.launch(Dispatchers.IO) {
            database.routingRuleDao().delete(rule)
            com.aigate.router.gateway.RoutingRuleManager.invalidateCache()
        }
    }

    /** 切换规则启用状态 */
    fun setRoutingRuleEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            database.routingRuleDao().setEnabled(id, enabled)
            com.aigate.router.gateway.RoutingRuleManager.invalidateCache()
        }
    }

    /** 清空所有路由规则 */
    fun clearAllRoutingRules() {
        viewModelScope.launch(Dispatchers.IO) {
            database.routingRuleDao().clearAll()
            com.aigate.router.gateway.RoutingRuleManager.invalidateCache()
        }
    }


private val _totalPromptTokens = MutableStateFlow(0L)
val totalPromptTokens: StateFlow<Long> = _totalPromptTokens.asStateFlow()

private val _totalCompletionTokens = MutableStateFlow(0L)
val totalCompletionTokens: StateFlow<Long> = _totalCompletionTokens.asStateFlow()

private val _totalTokensAll = MutableStateFlow(0L)
val totalTokensAll: StateFlow<Long> = _totalTokensAll.asStateFlow()

/** 刷新 Token 统计数据 */
fun refreshTokenStats() {
    viewModelScope.launch {
        try {
            val prompt = database.tokenUsageDao().getTotalPromptTokens()
            val completion = database.tokenUsageDao().getTotalCompletionTokens()
            val total = database.tokenUsageDao().getTotalTokens()
            _totalPromptTokens.value = prompt
            _totalCompletionTokens.value = completion
            _totalTokensAll.value = total
        } catch (_: Exception) { }
    }
}

    // ==================== 测速历史趋势图 ====================
    /** 测速历史 DAO */
    val speedHistoryDao: SpeedHistoryDao = database.speedHistoryDao()

    /** 当前选中的模型 key（用于趋势图） */
    private val _selectedHistoryModelKey = MutableStateFlow<String?>(null)
    val selectedHistoryModelKey: StateFlow<String?> = _selectedHistoryModelKey.asStateFlow()

    /** 当前选中模型的测速历史 */
    private val _selectedModelHistory = MutableStateFlow<List<SpeedHistory>>(emptyList())
    val selectedModelHistory: StateFlow<List<SpeedHistory>> = _selectedModelHistory.asStateFlow()

    /** 记录一条测速历史 */
    fun recordSpeedHistory(modelKey: String, modelName: String, providerId: Long, metrics: SpeedMetrics) {
        viewModelScope.launch {
            try {
                val success = metrics.ttftMs > 0
                val history = SpeedHistory(
                    modelKey = modelKey,
                    modelName = modelName,
                    providerId = providerId,
                    ttftMs = if (success) metrics.ttftMs else -1,
                    tps = if (success) metrics.tps else 0.0,
                    totalMs = if (success) metrics.totalMs else -1,
                    success = success,
                    measuredAt = metrics.measuredAt
                )
                withContext(Dispatchers.IO) {
                    database.speedHistoryDao().insert(history)
                    // 自动清理超过7天的旧记录
                    val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
                    database.speedHistoryDao().deleteOlderThan(weekAgo)
                }
                // 如果当前已选中该模型，刷新历史
                if (_selectedHistoryModelKey.value == modelKey) {
                    loadModelHistory(modelKey)
                }
            } catch (_: Exception) { }
        }
    }

    /** 加载指定模型的测速历史 */
    fun loadModelHistory(modelKey: String) {
        if (modelKey.isBlank()) {
            _selectedHistoryModelKey.value = null
            _selectedModelHistory.value = emptyList()
            return
        }
        _selectedHistoryModelKey.value = modelKey
        viewModelScope.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    database.speedHistoryDao().getHistoryByModelOnce(modelKey)
                }
                _selectedModelHistory.value = history
            } catch (_: Exception) { }
        }
    }

    /** 获取所有模型的最新测速 */
    val latestSpeedHistory: StateFlow<List<SpeedHistory>> = database.speedHistoryDao()
        .getLatestEachModel()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ==================== 备份管理器 ====================
    private val backupManager = BackupManager(database)

    // ==================== 添加服务商表单 ====================
    
    /** 大模型服务商类型预设 */
    data class ProviderTypePreset(
        val displayName: String,      // 显示名称
        val defaultType: String,       // 类型标识
        val defaultBaseUrl: String,    // 默认基础地址
        val defaultPort: String,       // 默认端口
        val exampleApiKey: String,      // API Key 提示
        val defaultChatPath: String = "/v1/chat/completions",  // 聊天接口路径
        val defaultApiPath: String = "/v1/models"               // 模型列表接口路径
    )
    
companion object {
            /** Идентификатор встроенной модели в списке моделей шлюза. */
            const val NANO_MODEL_ID = "gemini-nano"

            /** Предел входа Gemini Nano — около четырёх тысяч токенов. */
            const val NANO_CONTEXT_WINDOW = 4096

            val PROVIDER_TYPES = listOf(
                ProviderTypePreset(
                    displayName = "OpenAI",
                    defaultType = "OpenAI Compatible",
                    defaultBaseUrl = "https://api.openai.com",
                    defaultPort = "443",
                    exampleApiKey = "sk-..."
                ),
                ProviderTypePreset(
                    displayName = "Anthropic (Claude)",
                    defaultType = "Anthropic",
                    defaultBaseUrl = "https://api.anthropic.com",
                    defaultPort = "443",
                    exampleApiKey = "sk-ant-...",
                    defaultChatPath = "/v1/messages",
                    defaultApiPath = "/v1/models"
                ),
                ProviderTypePreset(
                    displayName = "Google (Gemini)",
                    defaultType = "Google Gemini",
                    defaultBaseUrl = "https://generativelanguage.googleapis.com",
                    defaultPort = "443",
                    exampleApiKey = "AIza..."
                ),
                ProviderTypePreset(
                    displayName = "DeepSeek",
                    defaultType = "OpenAI Compatible",
                    defaultBaseUrl = "https://api.deepseek.com",
                    defaultPort = "443",
                    exampleApiKey = "sk-..."
                ),
                ProviderTypePreset(
                    displayName = "Qwen (Тунъи Цяньвэнь)",
                    defaultType = "OpenAI Compatible",
                    defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode",
                    defaultPort = "443",
                    exampleApiKey = "sk-..."
                ),
                ProviderTypePreset(
                    displayName = "OpenRouter",
                    defaultType = "OpenAI Compatible",
                    defaultBaseUrl = "https://openrouter.ai/api",
                    defaultPort = "443",
                    exampleApiKey = "sk-or-..."
                ),
                ProviderTypePreset(
                    displayName = "Together",
                    defaultType = "OpenAI Compatible",
                    defaultBaseUrl = "https://api.together.xyz",
                    defaultPort = "443",
                    exampleApiKey = "api-..."
                ),
                ProviderTypePreset(
                    // Чат-API у Cursor не публичный: провайдер нужен только для
                    // расхода по админ-API, моделей он не даёт.
                    displayName = "Cursor (расход)",
                    defaultType = "Cursor Admin",
                    defaultBaseUrl = "https://api.cursor.com",
                    defaultPort = "443",
                    exampleApiKey = "key_...",
                    defaultChatPath = "",
                    defaultApiPath = ""
                ),
                ProviderTypePreset(
                    displayName = "Ollama (локально)",
                    defaultType = "Ollama",
                    defaultBaseUrl = "http://localhost",
                    defaultPort = "11434",
                    exampleApiKey = ""
                ),
                ProviderTypePreset(
                    displayName = "Custom (свой)",
                    defaultType = "Custom",
                    defaultBaseUrl = "",
                    defaultPort = "",
                    exampleApiKey = ""
                )
            )
        }

        private val jsonStatic = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun parseProxyList(jsonStr: String): List<ProxyProfile> {
            return try {
                jsonStatic.decodeFromString<List<ProxyProfile>>(jsonStr)
            } catch (_: Exception) { emptyList() }
        }

    data class ProviderForm(
    val name: String = "",
    val type: String = "OpenAI Compatible",
    val baseUrl: String = "",
    val port: String = "",
    val apiKey: String = "",
    val orderIndex: Int = 0,
    val chatPath: String = "", // 聊天接口路径（留空=自动拼接/v1/chat/completions）
    val apiPath: String = "/v1/models" // 模型列表接口路径
)

    private val _providerForm = MutableStateFlow(ProviderForm())
    val providerForm: StateFlow<ProviderForm> = _providerForm.asStateFlow()

    // ==================== 初始化 ====================
    init {
        refreshTokenStats()
        loadProxyListFromPrefs()
        // ★★ 智能检测网关服务是否真正在运行 ★★
        val spRunning = GatewayForegroundService.isServiceRunning
        var actualRunning = spRunning
        if (spRunning) {
            // 双重确认：检查前台服务是否真的存活
            try {
                val ctx = GatewayApplication.getInstance()
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningServices = am.getRunningServices(Int.MAX_VALUE)
                val isAlive = runningServices.any { it.service.shortClassName.contains("GatewayForegroundService") }
                if (!isAlive) {
                    // 服务已死但标记为运行中 → 修正状态
                    actualRunning = false
                    GatewayForegroundService.isServiceRunning = false
                    GatewayForegroundService.saveGatewayRunningState(false)
                }
            } catch (_: Exception) { }
        }
        _serviceRunning.value = actualRunning
        // ★★ 加载上次测速缓存到排行榜，同步本地模型（去重+移除不存在的+添加新模型）★★
        val cached = loadPipelineCache()
        if (cached.isNotEmpty()) {
            viewModelScope.launch {
                val enabledList = withContext(Dispatchers.IO) {
                    database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
                }
                val enabledByKey = enabledList.associateBy { it.routeKey }
                val cachedByKey = linkedMapOf<String, PipelineTestItem>()

                // Legacy cache entries without a provider are migrated only if unique.
                for (item in cached) {
                    val model = if (item.providerId > 0L) {
                        enabledByKey[item.selectionKey]
                    } else {
                        enabledList.filter { it.modelId == item.modelId }.singleOrNull()
                    } ?: continue
                    cachedByKey[model.routeKey] = item.copy(
                        modelName = model.customAlias.ifBlank { model.displayName },
                        providerId = model.providerId,
                    )
                }

                val merged = enabledList.map { model ->
                    cachedByKey[model.routeKey] ?: PipelineTestItem(
                        modelId = model.modelId,
                        modelName = model.customAlias.ifBlank { model.displayName },
                        providerId = model.providerId,
                        status = "Ожидание",
                        latencyMs = 0,
                        isCurrent = false
                    )
                }
                _pipelineStatus.value = merged
                val rankedKeys = merged
                    .filter { it.outcome != TestOutcome.Pending }
                    .sortedBy { it.latencyMs }
                    .map { it.selectionKey }
                com.aigate.router.gateway.GatewayScheduler.pipelineSortedModelKeys =
                    rankedKeys.ifEmpty { merged.map { it.selectionKey } }
                savePipelineCache(merged)
            }
        }
        // ★★ 初始化时检查自动故障转移是否已开启，若是则自动启动接力测速 ★★
        // 已移除自动启动测速 — 用户需要测速时手动点击"开始测速"按钮
        // 保留cache加载，让排行榜显示已有的缓存数据
        // ★★ 启动后台静默探针（30分钟循环，静默检测模型能力）★★
        ModelCapabilityManager.startSilentProbe(database, viewModelScope)
        // ★★ 测速开关 — 不自启测速，让用户手动点击"开始测速"按钮 ★★
    }

    // ========== 服务生命周期控制 ==========

    /** 启动网关服务 */
    fun startGateway() {
        try {
            GatewayForegroundService.start()
            _serviceRunning.value = true
            GatewayForegroundService.isServiceRunning = true
            GatewayForegroundService.saveGatewayRunningState(true)
        } catch (e: Exception) {
            _snackbarMessage.value = "Не удалось запустить шлюз: ${e.message}"
        }
    }

    /** 停止网关服务 */
    fun stopGateway() {
        try {
            GatewayForegroundService.stop()
            _serviceRunning.value = false
            GatewayForegroundService.isServiceRunning = false
            GatewayForegroundService.saveGatewayRunningState(false)
        } catch (e: Exception) {
            _snackbarMessage.value = "Не удалось остановить шлюз: ${e.message}"
        }
    }

    /** 切换网关状态 */
    fun toggleGateway() {
        if (_serviceRunning.value) stopGateway() else startGateway()
    }

    // ========== 网关端口配置 ==========

    /** 设置网关端口 */
    fun setGatewayPort(port: Int) {
        if (port in 1..65535) {
            _gatewayPort.value = port
            GatewayForegroundService.saveGatewayPort(port)
            _snackbarMessage.value = "Порт шлюза установлен на $port"
        } else {
            _snackbarMessage.value = "Диапазон порта: 1-65535"
        }
    }

    /** 获取当前网关端口 */
    fun getGatewayPort(): Int = _gatewayPort.value

    // ========== 代理管理（多代理列表 CRUD）==========

    /** 添加新代理 */
    fun addProxy(profile: ProxyProfile) {
        val list = _proxyProfiles.value.toMutableList()
        list.add(profile)
        _proxyProfiles.value = list
        saveProxyListToPrefs()
        _snackbarMessage.value = "Прокси «${profile.name}» добавлен"
    }

    /** 更新代理 */
    fun updateProxy(profile: ProxyProfile) {
        val list = _proxyProfiles.value.toMutableList()
        val index = list.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            list[index] = profile
            _proxyProfiles.value = list
            saveProxyListToPrefs()
            // 如果当前激活的就是这个代理，重新应用
            if (_activeProxyId.value == profile.id && _proxyEnabled.value) {
                applyProxyToNetwork(profile)
            }
            _snackbarMessage.value = "Прокси «${profile.name}» обновлён"
        }
    }

    /** 删除代理 */
    fun deleteProxy(profile: ProxyProfile) {
        val list = _proxyProfiles.value.toMutableList()
        list.removeAll { it.id == profile.id }
        _proxyProfiles.value = list
        // 如果删除的是当前激活的代理，停用代理
        if (_activeProxyId.value == profile.id) {
            _activeProxyId.value = null
            _proxyEnabled.value = false
            UpstreamClient.setProxy(null)
        }
        saveProxyListToPrefs()
        _snackbarMessage.value = "Прокси «${profile.name}» удалён"
    }

    /** 启用/禁用某个代理（选中即激活 — 互斥：开启一个时自动关闭其他） */
    fun toggleProxyEnabled(profile: ProxyProfile) {
        val newEnabled = !profile.enabled

        if (newEnabled) {
            // ★ 互斥逻辑：把其他所有代理设为 disabled，当前设为 enabled
            val list = _proxyProfiles.value.toMutableList()
            val newList = list.map { it.copy(enabled = it.id == profile.id) }
            _proxyProfiles.value = newList
            saveProxyListToPrefs()

            _activeProxyId.value = profile.id
            _proxyEnabled.value = true
            applyProxyToNetwork(profile.copy(enabled = true))
            _snackbarMessage.value = "Прокси «${profile.name}» включён (${profile.type})"
        } else {
            if (_activeProxyId.value == profile.id) {
                _activeProxyId.value = null
                _proxyEnabled.value = false
                UpstreamClient.setProxy(null)
            }
            val list = _proxyProfiles.value.toMutableList()
            val newList = list.map { it.copy(enabled = it.id != profile.id && it.enabled) }
            _proxyProfiles.value = newList
            saveProxyListToPrefs()
            _snackbarMessage.value = "Прокси «${profile.name}» выключен"
        }
    }

    /** 手动切换代理加速开关（如果当前有激活的代理则关闭，否则开启第一个启用的代理） */
    fun toggleProxy() {
        val activeId = _activeProxyId.value
        if (activeId != null && _proxyEnabled.value) {
            // 有关闭：停用代理
            _activeProxyId.value = null
            _proxyEnabled.value = false
            UpstreamClient.setProxy(null)
            _snackbarMessage.value = "Прокси выключен"
        } else {
            // 找第一个 enabled 的代理激活
            val firstEnabled = _proxyProfiles.value.firstOrNull { it.enabled }
            if (firstEnabled != null) {
                _activeProxyId.value = firstEnabled.id
                _proxyEnabled.value = true
                applyProxyToNetwork(firstEnabled)
                _snackbarMessage.value = "Прокси «${firstEnabled.name}» включён"
            } else {
                _snackbarMessage.value = "Нет доступных настроек прокси, сначала добавьте прокси"
            }
        }
    }

    /** 将代理配置应用到网络层 */
    private fun applyProxyToNetwork(profile: ProxyProfile) {
        try {
            val upstreamConfig = UpstreamClient.ProxyConfig(
                type = profile.type,
                host = profile.host,
                port = profile.port,
                username = profile.username,
                password = profile.password,
                enabled = true
            )
            UpstreamClient.setProxy(upstreamConfig)
        } catch (e: Exception) {
            _snackbarMessage.value = "Ошибка настройки прокси: ${e.message}"
        }
    }

    /** 智能测速 — 仅支持 HTTP/HTTPS/SOCKS5 */
    fun testProxySpeed(profile: ProxyProfile) {
        viewModelScope.launch {
            try {
                _snackbarMessage.value = "Тестирование ${profile.name}..."
                withContext(Dispatchers.IO) {
                    val upstreamConfig = UpstreamClient.ProxyConfig(
                        type = profile.type, host = profile.host, port = profile.port,
                        username = profile.username, password = profile.password, enabled = true
                    )
                    val tempClient = when (profile.type.uppercase()) {
                        "HTTP", "HTTPS" -> OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                            .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress(profile.host, profile.port)))
                            .build()
                        "SOCKS5", "SOCKS" -> OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                            .socketFactory(Socks5SocketFactory(profile.host, profile.port, profile.username, profile.password))
                            .build()
                        else -> { _snackbarMessage.value = "Замер скорости только для HTTP/HTTPS/SOCKS5"; return@withContext }
                    }

                    // ★★ 先测谷歌，通=海外，不通再测百度
                    var result = ""
                    try {
                        val start = System.currentTimeMillis()
                        val gOk = tempClient.newCall(okhttp3.Request.Builder().url("https://www.google.com/favicon.ico").build()).execute().isSuccessful
                        if (gOk) { result = "${profile.name}: ${System.currentTimeMillis() - start} мс (зарубежный доступ)" }
                    } catch (_: Exception) { }

                    if (result.isEmpty()) {
                        try {
                            val start = System.currentTimeMillis()
                            val bOk = tempClient.newCall(okhttp3.Request.Builder().url("https://www.baidu.com/favicon.ico").build()).execute().isSuccessful
                            if (bOk) { result = "${profile.name}: ${System.currentTimeMillis() - start} мс (доступ внутри страны)" }
                        } catch (_: Exception) { }
                    }

                    if (result.isEmpty()) { result = "${profile.name}: нет доступа ни внутри страны, ни за рубежом" }
                    _snackbarMessage.value = result
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "${profile.name} — замер скорости не удался: ${e.localizedMessage ?: e.message}"
            }
        }
    }

    /** 判断 host 是否为国内 IP/域名（用于智能选择测速目标） */
    private fun isChineseHost(host: String): Boolean {
        // .cn 域名直接判定为国内
        if (host.endsWith(".cn")) return true
        // 常见国内 IP 段
        if (host.startsWith("10.") || host.startsWith("172.16.") || host.startsWith("192.168.")) return true
        // 尝试匹配私有IP段
        val ipv4Parts = host.split(".").mapNotNull { it.toIntOrNull() }
        if (ipv4Parts.size == 4) {
            val first = ipv4Parts[0]
            // 国内常见公网IP段
            if (first == 1 || first == 14 || first == 27 || first == 36 || first == 39 ||
                first == 42 || first == 49 || first == 58 || first == 59 || first == 60 ||
                first == 61 || first == 101 || first == 103 || first == 106 || first == 110 ||
                first == 111 || first == 112 || first == 113 || first == 114 || first == 115 ||
                first == 116 || first == 117 || first == 118 || first == 119 || first == 120 ||
                first == 121 || first == 122 || first == 123 || first == 124 || first == 125 ||
                first == 139 || first == 140 || first == 144 || first == 150 || first == 152 ||
                first == 153 || first == 157 || first == 158 || first == 159 || first == 160 ||
                first == 161 || first == 162 || first == 163 || first == 166 || first == 167 ||
                first == 168 || first == 169 || first == 170 || first == 171 || first == 172 ||
                first == 175 || first == 180 || first == 182 || first == 183 || first == 202 ||
                first == 203 || first == 210 || first == 211 || first == 218 || first == 219 ||
                first == 220 || first == 221 || first == 222 || first == 223) return true
        }
        return false
    }

    /** 从 SharedPreferences 加载代理列表 */
    fun loadProxyListFromPrefs() {
        try {
            val jsonStr = GatewayForegroundService.getProxyListJson()
            if (jsonStr.isNotBlank()) {
                val list = json.decodeFromString<List<ProxyProfile>>(jsonStr)
                _proxyProfiles.value = list
                // 恢复激活状态
                val enabledOne = list.firstOrNull { it.enabled }
                if (enabledOne != null) {
                    _activeProxyId.value = enabledOne.id
                    _proxyEnabled.value = true
                    applyProxyToNetwork(enabledOne)
                }
            }
        } catch (_: Exception) {
            // 初次使用或格式异常，空列表
        }
    }

    /** 保存代理列表到 SharedPreferences */
    private fun saveProxyListToPrefs() {
        try {
            val jsonStr = json.encodeToString(_proxyProfiles.value)
            GatewayForegroundService.saveProxyListJson(jsonStr)
        } catch (_: Exception) { }
    }

    // ========== 订阅导入 & 剪贴板检测 ==========

    /** 解析并批量导入订阅链接 */
    fun importSubscription(url: String) {
        viewModelScope.launch {
            try {
                _snackbarMessage.value = "Получение подписки..."
                withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder().url(url).build()
                    val response = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build().newCall(request).execute()

                    if (!response.isSuccessful) {
                        _snackbarMessage.value = "Не удалось получить подписку: HTTP ${response.code}"
                        return@withContext
                    }
                    val body = response.body?.string() ?: ""
                    if (body.isBlank()) {
                        _snackbarMessage.value = "Содержимое подписки пусто"
                        return@withContext
                    }

                    val parsed = com.aigate.router.network.ProxyLinkParser.parseBatch(body)
                    if (parsed.isEmpty()) {
                        _snackbarMessage.value = "Действительные узлы не найдены"
                        return@withContext
                    }

                    // 批量导入
                    val list = _proxyProfiles.value.toMutableList()
                    var added = 0
                    for (info in parsed) {
                        val exists = list.any { it.host == info.host && it.port == info.port }
                        if (!exists) {
                            val profile = ProxyProfile(
                                name = info.name, type = info.type, host = info.host, port = info.port,
                                enabled = false
                            )
                            list.add(profile)
                            added++
                        }
                    }
                    _proxyProfiles.value = list
                    saveProxyListToPrefs()
                    _snackbarMessage.value = "Импортировано узлов: $added"
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "Не удалось импортировать подписку: ${e.message}"
            }
        }
    }

    /** 解析单条代理链接并快速添加 */
    fun addProxyFromLink(link: String) {
        try {
            val info = com.aigate.router.network.ProxyLinkParser.parse(link)
            if (info != null) {
                val profile = ProxyProfile(
                    name = info.name, type = info.type, host = info.host, port = info.port,
                    enabled = false
                )
                addProxy(profile)
            } else {
                _snackbarMessage.value = "Не удалось разобрать ссылку прокси"
            }
        } catch (e: Exception) {
            _snackbarMessage.value = "Ошибка разбора: ${e.message}"
        }
    }

    /** 检测剪贴板中的代理链接/订阅链接（仅支持 HTTP/HTTPS/SOCKS5） */
    fun detectClipboardLink(clipText: String): String? {
        if (clipText.isBlank()) return null
        return when {
            clipText.startsWith("http://") && (clipText.contains("subscribe") || clipText.contains("sub") || clipText.contains("token=")) -> clipText
            clipText.startsWith("https://") && (clipText.contains("subscribe") || clipText.contains("sub") || clipText.contains("token=")) -> clipText
            else -> null
        }
    }
    fun bindBackgroundPermissions() {
        viewModelScope.launch {
            try {
                _snackbarMessage.value = "Запрос фоновых разрешений..."
                withContext(Dispatchers.IO) {
                    try {
                        Runtime.getRuntime().exec(arrayOf(
                            "dumpsys", "deviceidle", "whitelist",
                            "+com.aigate.router"
                        )).waitFor()
                    } catch (_: Exception) { }

                    try {
                        Runtime.getRuntime().exec(arrayOf(
                            "appops", "set", "com.aigate.router",
                            "RUN_ANY_IN_BACKGROUND", "allow"
                        )).waitFor()
                    } catch (_: Exception) { }

                    try {
                        Runtime.getRuntime().exec(arrayOf(
                            "cmd", "deviceidle", "whitelist",
                            "+com.aigate.router"
                        )).waitFor()
                    } catch (_: Exception) { }
                }
                _snackbarMessage.value = "Фоновые разрешения привязаны! Разрешите автозапуск в настройках системы"
            } catch (e: Exception) {
                _snackbarMessage.value = "Часть разрешений не получена (возможно, нужен Root): ${e.message}"
            }
        }
    }

    // ========== 关于我们连点（改为打开代理管理页面）==========

    /** 重置关于我们连点计数 */
    fun resetAboutClickCount() {
        _aboutClickCount.value = 0
    }

    /** 增加关于我们点击计数，达到3次时打开代理管理页面 */
    fun incrementAboutClick() {
        val now = System.currentTimeMillis()
        // 3秒内连点3次才计数
        if (now - _lastClickTime > 3000) {
            _aboutClickCount.value = 1
        } else {
            val newCount = _aboutClickCount.value + 1
            _aboutClickCount.value = newCount
            if (newCount >= 3) {
                // 连点3次成功，打开代理管理页面
                showProxyConfig()
                resetAboutClickCount()
            }
        }
        _lastClickTime = now
    }

    // ========== 服务商 CRUD ==========

    fun showAddProvider() {
        _providerForm.value = ProviderForm()
        _showAddProviderDialog.value = true
    }
    
    /** 根据预设类型自动填充表单 */
    fun selectProviderType(index: Int) {
        if (index < 0 || index >= PROVIDER_TYPES.size) return
        val preset = PROVIDER_TYPES[index]
        _providerForm.value = ProviderForm(
            name = preset.displayName,
            type = preset.defaultType,
            baseUrl = preset.defaultBaseUrl,
            port = preset.defaultPort,
            apiKey = preset.exampleApiKey,
            chatPath = preset.defaultChatPath,
            apiPath = preset.defaultApiPath
        )
    }

    fun hideAddProvider() {
        _showAddProviderDialog.value = false
    }

    fun showEditProvider(provider: Provider) {
        _showEditProviderDialog.value = provider
    }

    fun hideEditProvider() {
        _showEditProviderDialog.value = null
    }

    /** 显示代理配置弹窗 */
    fun showProxyConfig() {
        _showProxyConfigDialog.value = true
    }

    /** 隐藏代理配置弹窗 */
    fun hideProxyConfig() {
        _showProxyConfigDialog.value = false
    }

    fun updateFormField(name: String, value: String) {
        _providerForm.value = when (name) {
            "name" -> _providerForm.value.copy(name = value)
            "type" -> _providerForm.value.copy(type = value)
            "baseUrl" -> _providerForm.value.copy(baseUrl = value)
            "port" -> _providerForm.value.copy(port = value)
            "apiKey" -> _providerForm.value.copy(apiKey = value)
            "chatPath" -> _providerForm.value.copy(chatPath = value)
            "apiPath" -> _providerForm.value.copy(apiPath = value)
            "orderIndex" -> _providerForm.value.copy(orderIndex = value.toIntOrNull() ?: 0)
            else -> _providerForm.value
        }
    }

    /** 从 Base URL 中自动提取端口号 */
    fun extractPortFromUrl(url: String): String {
        if (url.isBlank()) return ""
        return try {
            val regex = Regex("://[^:]+:(\\d+)")
            regex.find(url)?.groupValues?.getOrNull(1) ?: ""
        } catch (_: Exception) { "" }
    }

    /** 保存新服务商 */
    /** Показать сообщение пользователю (снекбар). */
    fun showMessage(text: String) {
        _snackbarMessage.value = text
    }

    /**
     * Подключить провайдера из каталога: адрес, порт и путь берутся из пресета,
     * пользователь вводит только ключ (или адрес для локального сервера).
     * Сразу после сохранения запрашиваем у провайдера список моделей.
     */
    fun connectFromCatalog(
        presetIndex: Int,
        name: String,
        apiKey: String,
        baseUrlOverride: String? = null,
    ) {
        val preset = PROVIDER_TYPES.getOrNull(presetIndex) ?: return
        viewModelScope.launch {
            try {
                val base = (baseUrlOverride ?: preset.defaultBaseUrl).trimEnd('/')
                val port = if (baseUrlOverride != null) {
                    extractPortFromUrl(baseUrlOverride)
                } else preset.defaultPort
                val newId = database.providerDao().insert(
                    Provider(
                        name = name,
                        type = preset.defaultType,
                        baseUrl = base,
                        port = port,
                        credentialId = 0,
                        chatPath = preset.defaultChatPath.takeIf { it.isNotBlank() }
                    )
                )
                if (apiKey.isNotBlank()) {
                    val credId = CredentialStore.setApiKey(database, newId, apiKey)
                    if (credId != 0L) {
                        database.providerDao().getProviderById(newId)?.let {
                            database.providerDao().update(it.copy(credentialId = credId))
                        }
                    }
                }
                _snackbarMessage.value = "«$name» подключён, запрашиваю модели"
                database.providerDao().getProviderById(newId)?.let { syncModels(it) }
            } catch (e: Exception) {
                _snackbarMessage.value = "Не удалось подключить: ${e.message}"
            }
        }
    }

    fun saveProvider() {
        val form = _providerForm.value
        if (form.name.isBlank()) {
            _snackbarMessage.value = "Введите название провайдера"
            return
        }
        if (form.baseUrl.isBlank()) {
            _snackbarMessage.value = "Введите адрес API"
            return
        }

        viewModelScope.launch {
            try {
                val newId = database.providerDao().insert(
                    Provider(
                        name = form.name,
                        type = form.type,
                        baseUrl = form.baseUrl.trimEnd('/'),
                        port = form.port,
                        credentialId = 0,
                        orderIndex = form.orderIndex,
                        chatPath = form.chatPath.ifBlank { null }
                    )
                )
                val credId = CredentialStore.setApiKey(database, newId, form.apiKey)
                if (credId != 0L) {
                    database.providerDao().getProviderById(newId)?.let { inserted ->
                        database.providerDao().update(inserted.copy(credentialId = credId))
                    }
                }
                _showAddProviderDialog.value = false
                _snackbarMessage.value = "Провайдер «${form.name}» добавлен, запрашиваю модели"
                // Модели у провайдера спрашиваем сами: вводить их руками не нужно.
                database.providerDao().getProviderById(newId)?.let { syncModels(it) }
            } catch (e: Exception) {
                _snackbarMessage.value = "Не удалось добавить: ${e.message}"
            }
        }
    }

    /**
     * Сохранить изменения провайдера (в том числе переименование).
     *
     * У провайдера с OAuth-сессией (Codex, Claude Code) ключа API нет. Запись
     * «ключа» превратила бы сессию в обычный ключ: тип credential менялся на
     * api_key, автообновление переставало работать, и после истечения токена
     * провайдер умирал молча. Поэтому там меняются только поля провайдера.
     */
    fun updateProvider(provider: Provider, apiKey: String) {
        viewModelScope.launch {
            try {
                val existing = database.credentialDao().getByProvider(provider.id)
                if (existing?.type == com.aigate.router.data.model.Credential.TYPE_OAUTH) {
                    database.providerDao().update(provider.copy(credentialId = existing.id))
                } else {
                    val credId = CredentialStore.setApiKey(database, provider.id, apiKey)
                    database.providerDao().update(provider.copy(credentialId = credId))
                }
                // Имя ресурса следует за именем провайдера — обновляем пулы сразу,
                // иначе плитка на «Обзоре» осталась бы с прежним именем до сброса.
                runCatching {
                    com.aigate.router.quota.QuotaRefresher.refresh(
                        appContext, database, com.aigate.router.quota.RefreshTrigger.USER_ACTION
                    )
                }
                _showEditProviderDialog.value = null
                _snackbarMessage.value = "Провайдер обновлён"
            } catch (e: Exception) {
                _snackbarMessage.value = "Не удалось обновить: ${e.message}"
            }
        }
    }

    /** 删除服务商 */
    fun deleteProvider(provider: Provider) {
        viewModelScope.launch {
            try {
                database.providerDao().delete(provider)
                // 同时删除该服务商的凭据
                CredentialStore.deleteForProvider(database, provider.id)
                // 同时删除该服务商下的所有模型
                database.aiModelDao().deleteByProvider(provider.id)
                _snackbarMessage.value = "Провайдер «${provider.name}» и связанные модели удалены"
            } catch (e: Exception) {
                _snackbarMessage.value = "Не удалось удалить: ${e.message}"
            }
        }
    }

    /** 移动服务商排序（上下） */
    fun moveProvider(provider: com.aigate.router.data.model.Provider, direction: Int) {
        viewModelScope.launch {
            try {
                val allProviders = database.providerDao().getAllProvidersOnce().sortedBy { it.orderIndex }
                val idx = allProviders.indexOfFirst { it.id == provider.id }
                if (idx < 0) return@launch
                val targetIdx = idx + direction
                if (targetIdx < 0 || targetIdx >= allProviders.size) return@launch
                val target = allProviders[targetIdx]
                // 交换 orderIndex
                database.providerDao().update(provider.copy(orderIndex = target.orderIndex))
                database.providerDao().update(target.copy(orderIndex = provider.orderIndex))
            } catch (_: Exception) { }
        }
    }

    /** 切换服务商启用状态 */
    fun toggleProviderEnabled(provider: Provider) {
        viewModelScope.launch {
            try {
                database.providerDao().update(
                    provider.copy(isEnabled = !provider.isEnabled)
                )
            } catch (e: Exception) {
                _snackbarMessage.value = "Операция не удалась: ${e.message}"
            }
        }
    }

    // ========== 模型同步 ==========
/** 从服务商同步模型列表（支持平台预设模型） */
    fun syncModels(provider: Provider) {
        viewModelScope.launch {
            _syncingProviderId.value = provider.id
            _syncResult.value = null

            // У Codex список моделей отдаёт бэкенд ChatGPT, а не /v1/models.
            if (com.aigate.router.gateway.CodexUpstream.isCodex(provider)) {
                val count = withContext(Dispatchers.IO) {
                    runCatching {
                        com.aigate.router.auth.CliSessionManager.syncCodexModels(database, provider.id)
                    }.getOrNull()
                }
                _syncingProviderId.value = null
                _syncResult.value = if (count != null) {
                    "${provider.name}: моделей — $count"
                } else {
                    "${provider.name}: сервер не отдал список моделей"
                }
                return@launch
            }

            // Локальные провайдеры ни о чём не спрашивают сеть: список
            // системной модели известен заранее, а список скачанных ведёт
            // каталог. Обычная ветка попыталась бы сходить по адресу
            // «local://», которого не существует.
            if (com.aigate.router.gateway.local.LocalBackendRegistry.ownsType(provider.type)) {
                val message = withContext(Dispatchers.IO) { syncLocalProvider(provider) }
                _syncingProviderId.value = null
                _syncResult.value = message
                return@launch
            }

            // У подписки Claude каталог может быть закрыт для токена — там свой
            // путь с запасным списком, поэтому обычная ветка не подходит.
            if (provider.type.equals(com.aigate.router.auth.ClaudeCliAuth.PROVIDER_TYPE, true)) {
                val count = withContext(Dispatchers.IO) {
                    runCatching {
                        com.aigate.router.auth.CliSessionManager.syncClaudeModels(database, provider.id)
                    }.getOrNull()
                }
                _syncingProviderId.value = null
                _syncResult.value = if (count != null) {
                    "${provider.name}: моделей — $count"
                } else {
                    "${provider.name}: список моделей получить не удалось"
                }
                return@launch
            }

            try {
                withContext(Dispatchers.IO) {
                    // Anthropic и Gemini отвечают своим форматом и своей
                    // авторизацией — спрашиваем у них реальный список моделей
                    // вместо прежнего захардкоженного перечня.
                    val family = com.aigate.router.network.ModelCatalogApi.familyOf(provider)
                    if (family != com.aigate.router.network.ModelCatalogApi.Family.OPENAI_COMPATIBLE) {
                        val remote = com.aigate.router.network.ModelCatalogApi.fetch(
                            provider = provider,
                            apiKey = CredentialStore.apiKeyForProvider(provider)
                        )
                        if (remote.isNullOrEmpty()) {
                            _syncResult.value = "${provider.name}: провайдер не отдал список моделей"
                            _snackbarMessage.value = "Список моделей получить не удалось"
                            _syncingProviderId.value = null
                            return@withContext
                        }
                        val existingModels = database.aiModelDao().getModelsByProvider(provider.id)
                        val enabledMap = existingModels.associate { it.modelId to it.isEnabled }
                        database.aiModelDao().deleteByProvider(provider.id)
                        database.aiModelDao().insertAll(
                            remote.map { m ->
                                AiModel(
                                    providerId = provider.id,
                                    modelId = m.id,
                                    displayName = m.displayName,
                                    syncStatus = "Synced",
                                    isEnabled = enabledMap[m.id] ?: true,
                                    customAlias = ""
                                )
                            }
                        )
                        _syncResult.value = "${provider.name}: моделей — ${remote.size}"
                        _snackbarMessage.value = "Моделей получено: ${remote.size}"
                        return@withContext
                    }

                    // ★★ 其他平台：正常调用 fetchModels ★★
                    val resolvedUrl = provider.resolvedBaseUrl
                    val response = UpstreamClient.fetchModels(
                        baseUrl = resolvedUrl,
                        apiKey = CredentialStore.apiKeyForProvider(provider)
                    )

                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "Неизвестная ошибка"
                        _syncResult.value = "Синхронизация не удалась (${response.code}): $errorBody"
                        _snackbarMessage.value = "Синхронизация моделей не удалась: HTTP ${response.code}"
                        _syncingProviderId.value = null
                        return@withContext
                    }

                    val responseBody = response.body?.string() ?: "{}"
                    val jsonObj = json.parseToJsonElement(responseBody).jsonObject
                    val dataArray = jsonObj["data"]?.jsonArray

                    if (dataArray == null) {
                        _syncResult.value = "В ответе не найден список моделей"
                        _snackbarMessage.value = "Синхронизация моделей не удалась: неверный формат ответа"
                        _syncingProviderId.value = null
                        return@withContext
                    }

                    // 修复：同步模型时保留已有模型的启用状态
                    val existingModels = database.aiModelDao().getModelsByProvider(provider.id)
                    val enabledMap = existingModels.associate { it.modelId to it.isEnabled }

                    val models = dataArray.mapNotNull { element ->
                        val obj = element.jsonObject
                        val modelId = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val displayName = obj["display_name"]?.jsonPrimitive?.content
                            ?: obj["id"]?.jsonPrimitive?.content
                            ?: modelId
                        AiModel(
                            providerId = provider.id,
                            modelId = modelId,
                            displayName = displayName,
                            syncStatus = "Synced",
                            isEnabled = enabledMap[modelId] ?: false,
                            customAlias = ""
                        )
                    }

                    if (models.isEmpty()) {
                        _syncResult.value = "Провайдер вернул пустой список моделей"
                        _snackbarMessage.value = "Синхронизация завершена, но модели не найдены"
                    } else {
                        database.aiModelDao().deleteByProvider(provider.id)
                        database.aiModelDao().insertAll(models)
                        _syncResult.value = "Синхронизировано моделей: ${models.size}"
                        _snackbarMessage.value = "Синхронизировано моделей: ${models.size}"
                    }
                }
            } catch (e: Exception) {
                _syncResult.value = "Ошибка синхронизации: ${e.message}"
                _snackbarMessage.value = "Синхронизация моделей не удалась: ${e.message}"
            } finally {
                _syncingProviderId.value = null
            }
        }
    }

    /**
     * Подключение встроенной модели одним шагом.
     *
     * Ни адреса, ни ключа у неё нет — провайдер описывает саму возможность
     * устройства. Если системе ещё нужно скачать модель, загрузка запускается
     * здесь же: пользователь только что явно об этом попросил, и это
     * единственное место, где такое ожидаемо.
     */
    suspend fun connectDeviceModel(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val type = com.aigate.router.gateway.local.LocalBackendRegistry.TYPE_NANO
            val availability = com.aigate.router.gateway.local.nano.AiCoreStatus.availability()
            if (availability == com.aigate.router.gateway.local.nano.AiCoreStatus.Availability.UNAVAILABLE) {
                error("Встроенная модель недоступна на этом устройстве")
            }

            val existing = database.providerDao().getAllProvidersList()
                .firstOrNull { it.type.equals(type, ignoreCase = true) }
            val provider = existing ?: Provider(
                name = "Системная модель",
                type = type,
                baseUrl = "local://device",
                isEnabled = true,
                orderIndex = database.providerDao().getAllProvidersList().size,
            ).let { it.copy(id = database.providerDao().insert(it)) }

            if (availability == com.aigate.router.gateway.local.nano.AiCoreStatus.Availability.DOWNLOADABLE) {
                // Загрузку запускаем и не ждём: она весит гигабайты, и держать
                // ради неё нажатие «подключить» значило бы повесить экран на
                // несколько минут. Область — уровня приложения, а не экрана:
                // уход с экрана не должен обрывать загрузку на середине.
                com.aigate.router.gateway.local.nano.AiCoreStatus.startDownload(
                    GatewayApplication.getInstance().applicationScope
                )
            }

            syncLocalProvider(provider)
            _snackbarMessage.value = when (availability) {
                com.aigate.router.gateway.local.nano.AiCoreStatus.Availability.AVAILABLE ->
                    "Системная модель подключена"

                else -> "Система скачивает встроенную модель"
            }
            Unit
        }
    }

    /**
     * Список моделей локального провайдера.
     *
     * У системной модели он ровно из одной строки, и её состояние определяет
     * система: пока модель не скачана, строка заводится выключенной — так
     * пользователь видит, что модель есть, но пока не отвечает, а шлюз её не
     * трогает. У движков скачанных моделей список ведёт каталог.
     */
    private suspend fun syncLocalProvider(provider: Provider): String {
        if (provider.type != com.aigate.router.gateway.local.LocalBackendRegistry.TYPE_NANO) {
            runCatching { com.aigate.router.download.LocalModelSync.sync(database) }
            val count = database.aiModelDao().getModelsByProvider(provider.id).size
            return "${provider.name}: моделей — $count"
        }

        com.aigate.router.gateway.local.nano.AiCoreStatus.invalidate()
        val availability = com.aigate.router.gateway.local.nano.AiCoreStatus.availability()
        val existing = database.aiModelDao().getModelsByProvider(provider.id)
            .firstOrNull { it.modelId == NANO_MODEL_ID }

        if (availability == com.aigate.router.gateway.local.nano.AiCoreStatus.Availability.UNAVAILABLE) {
            // Строку не удаляем: устройство могло временно потерять доступ, а
            // выключенная модель честнее исчезнувшей — видно, что она есть и
            // почему молчит. Но включённой её оставлять нельзя: шлюз пошёл бы
            // к ней и вернул клиенту ошибку.
            existing?.takeIf { it.isEnabled }?.let { database.aiModelDao().update(it.copy(isEnabled = false)) }
            return "Встроенная модель недоступна на этом устройстве"
        }

        val enabled = availability == com.aigate.router.gateway.local.nano.AiCoreStatus.Availability.AVAILABLE
        if (existing == null) {
            database.aiModelDao().insert(
                AiModel(
                    providerId = provider.id,
                    modelId = NANO_MODEL_ID,
                    displayName = "Gemini Nano (на устройстве)",
                    syncStatus = "Synced",
                    isEnabled = enabled,
                    useProxy = false,
                    contextWindow = NANO_CONTEXT_WINDOW,
                )
            )
        } else if (existing.isEnabled != enabled) {
            database.aiModelDao().update(existing.copy(isEnabled = enabled))
        }

        return when (availability) {
            com.aigate.router.gateway.local.nano.AiCoreStatus.Availability.AVAILABLE ->
                "Встроенная модель готова"

            com.aigate.router.gateway.local.nano.AiCoreStatus.Availability.DOWNLOADING ->
                "Система скачивает встроенную модель"

            else -> "Встроенную модель нужно скачать"
        }
    }

    /** 显示Snackbar信息 */
    fun showSnackbar(msg: String) {
        _snackbarMessage.value = msg
    }

/** 选择模型（选中即启用，取消选中即暂停） */
fun selectModel(model: AiModel?) {
    _selectedModel.value = model
    // 选中模型时，自动启用该模型
    if (model != null && !model.isEnabled) {
        viewModelScope.launch {
            try {
                database.aiModelDao().update(model.copy(isEnabled = true))
            } catch (e: Exception) {
                _snackbarMessage.value = "Не удалось включить модель: ${e.message}"
            }
        }
    }
}


    /** 切换模型启用/暂停状态 */
    fun toggleModelEnabled(model: AiModel) {
        viewModelScope.launch {
            try {
                val newEnabled = !model.isEnabled
                database.aiModelDao().update(
                    model.copy(isEnabled = newEnabled)
                )
                // 如果暂停的是当前选中的模型，清除选中状态
                if (_selectedModel.value?.id == model.id && !newEnabled) {
                    _selectedModel.value = null
                }
                _snackbarMessage.value = if (newEnabled) {
                    "Модель включена"
                } else {
                    "Модель приостановлена"
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "Операция не удалась: ${e.message}"
            }
        }
    }

    /** 打开模型编辑别名对话框 */
    fun showEditModelAlias(model: AiModel) {
        _showEditModelDialog.value = model
    }

    /** 隐藏模型编辑对话框 */
    fun hideEditModelAlias() {
        _showEditModelDialog.value = null
    }

    /** 手动添加模型（无需同步，直接写入数据库） */
    fun manualAddModel(providerId: Long, modelId: String, displayName: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 检查是否已存在
                val existing = database.aiModelDao().getModelByProviderAndId(providerId, modelId)
                if (existing != null) {
                    onResult(false, "Модель $modelId уже существует")
                    return@launch
                }
                val model = AiModel(
                    providerId = providerId,
                    modelId = modelId,
                    displayName = displayName.ifBlank { modelId },
                    syncStatus = "Manual",
                    isEnabled = true
                )
                database.aiModelDao().insert(model)
                onResult(true, "Модель $modelId добавлена")
            } catch (e: Exception) {
                onResult(false, "Не удалось добавить: ${e.message}")
            }
        }
    }

    /** 保存模型自定义别名 */
fun saveModelAlias(model: AiModel, alias: String) {
    viewModelScope.launch {
        try {
            database.aiModelDao().update(
                model.copy(customAlias = alias)
            )
            _snackbarMessage.value = if (alias.isNotBlank()) {
                "Псевдоним обновлён: $alias"
            } else {
                "Восстановлено имя по умолчанию"
            }
        } catch (e: Exception) {
            _snackbarMessage.value = "Не удалось сохранить псевдоним: ${e.message}"
        }
    }
}

/** 切换模型是否走代理（按模型粒度控制代理） */
fun toggleModelProxy(model: AiModel) {
    viewModelScope.launch {
        try {
            val newUseProxy = !model.useProxy
            database.aiModelDao().update(model.copy(useProxy = newUseProxy))
            val status = if (newUseProxy) "через прокси" else "прямое подключение"
            _snackbarMessage.value = "${model.displayName} переключён на $status"
        } catch (e: Exception) {
            _snackbarMessage.value = "Не удалось настроить прокси модели: ${e.message}"
        }
    }
}

    /** 获取模型的显示名称（优先使用自定义别名） */
fun getDisplayModelName(model: AiModel): String {
    // ★★ auto 虚拟模型特殊显示
    if (VirtualModel.isVirtual(model.modelId)) {
        return localizeGeneratedName("Автопереключение")
    }
    return if (model.customAlias.isNotBlank()) {
        "${localizeGeneratedName(model.displayName)} (${model.customAlias})"
    } else {
        localizeGeneratedName(model.displayName)
    }
    }

    // ========== Token 统计 ==========

    /** 获取指定服务商的 Token 总量 */
    suspend fun getTotalTokensByProvider(providerId: Long): Long = database.tokenUsageDao()
        .getTotalTokensByProvider(providerId)

    /** 获取指定模型的 Token 总量 */
    suspend fun getTotalTokensByModel(modelId: String): Long = database.tokenUsageDao()
        .getTotalTokensByModel(modelId)

    /** 清除所有用量记录 */
    fun clearAllUsage() {
        viewModelScope.launch {
            try {
                database.tokenUsageDao().clearAll()
                refreshTokenStats()
                _snackbarMessage.value = "Записи об использовании очищены"
            } catch (e: Exception) {
                _snackbarMessage.value = "Не удалось очистить: ${e.message}"
            }
        }
    }

    // ========== 数据备份与恢复 ==========

    /** 导出所有数据为备份 JSON（旧版兼容） */
    fun backupData() {
        viewModelScope.launch {
            _snackbarMessage.value = "Используйте новую функцию резервного копирования"
        }
    }

    /** 获取备份 JSON 字符串（供 UI 调用） */
    suspend fun getBackupJson(): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val dir = backupManager.getBackupDir()
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                val file = java.io.File(dir, "backup_$timestamp.qtbk")
                val result = backupManager.exportToFile(file)
                if (result.isSuccess) {
                    Result.success(file.absolutePath)
                } else {
                    Result.failure(result.exceptionOrNull() ?: Exception("Не удалось экспортировать"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** 从文件恢复数据 */
    suspend fun restoreFromFile(filePath: String, password: String = ""): Result<Unit> {
        return withContext(Dispatchers.IO) {
            backupManager.importFromFile(java.io.File(filePath), password)
        }
    }

    /** 从 JSON 字符串恢复数据（旧版兼容） */
    suspend fun restoreFromJson(jsonString: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            Result.failure(Exception("Импорт из старого JSON больше не поддерживается, используйте файл .qtbk"))
        }
    }

    /** 重置所有数据 */
    fun resetAllData() {
        viewModelScope.launch {
            try {
                // 清空所有表 - 直接使用 DAO 清除
                database.tokenUsageDao().clearAll()
                database.aiModelDao().deleteAll()
                database.providerDao().deleteAll()
                _snackbarMessage.value = "Все данные сброшены"
            } catch (e: Exception) {
                _snackbarMessage.value = "Не удалось сбросить: ${e.message}"
            }
        }
    }

    // ========== 自动获取模型列表 ==========

    /** 根据 Base URL 自动获取可用模型列表 */
    fun fetchAvailableModels(baseUrl: String, apiKey: String? = null) {
        viewModelScope.launch {
            try {
                _syncResult.value = null
                val apiPath = _providerForm.value.apiPath
                withContext(Dispatchers.IO) {
                    val response = UpstreamClient.fetchModels(
                        baseUrl = baseUrl.trimEnd('/'),
                        apiKey = apiKey,
                        apiPath = apiPath
                    )

                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "Неизвестная ошибка"
                        _syncResult.value = "Не удалось получить модели (${response.code}): $errorBody"
                        return@withContext
                    }

                    val responseBody = response.body?.string() ?: "{}"
                    val jsonObj = json.parseToJsonElement(responseBody).jsonObject
                    val dataArray = jsonObj["data"]?.jsonArray

                    if (dataArray == null) {
                        _syncResult.value = "В ответе не найден список моделей, но подключение успешно"
                        return@withContext
                    }

                    val modelNames = dataArray.mapNotNull { element ->
                        val obj = element.jsonObject
                        obj["id"]?.jsonPrimitive?.content
                    }

                    _syncResult.value = "Получено ${modelNames.size} моделей: ${modelNames.joinToString(", ")}"
                }
            } catch (e: Exception) {
                _syncResult.value = "Не удалось получить список моделей: ${e.message}"
            }
        }
    }

    // ★★ 三指标测速器（单例复用） ★★
    private val speedTester = ModelSpeedTester()

    /**
     * Один замер модели у её провайдера. Формат апстрима (Codex/Anthropic/OpenAI)
     * и идентичность подписки собираются здесь, чтобы одиночный замер, обход всех
     * моделей и конвейер не расходились. OAuth-токен освежается заранее — замер
     * после простоя иначе упирался бы в просроченный access-токен.
     */
    private suspend fun measureModel(model: AiModel, provider: Provider): SpeedMetrics {
        // Локальная модель считается в этом же процессе: ни адреса, ни ключа у
        // неё нет, поэтому замер идёт через движок. Ветка стоит здесь одна на
        // все три вызова — одиночный замер, обход всех моделей и конвейер.
        if (com.aigate.router.gateway.local.LocalBackendRegistry.ownsType(provider.type)) {
            return com.aigate.router.gateway.local.LocalSpeedMeasurer.measure(model.modelId, provider.type)
        }
        com.aigate.router.auth.AuthRegistry.ensureFreshForProvider(database, provider)
        val key = CredentialStore.apiKeyForProvider(provider)
        val isCodex = com.aigate.router.gateway.CodexUpstream.isCodex(provider)
        return speedTester.measure(
            modelId = model.modelId,
            baseUrl = provider.resolvedBaseUrl,
            apiKey = key,
            chatPath = provider.chatPath,
            useResponsesApi = isCodex,
            accountId = if (isCodex) com.aigate.router.auth.CodexAccount.headerAccountId(
                database.credentialDao().getByProvider(provider.id)?.accountId, key
            ) else null,
            useMessagesApi = com.aigate.router.gateway.AnthropicUpstream.isAnthropic(provider),
            claudeSubscription = com.aigate.router.gateway.AnthropicUpstream.isSubscription(provider),
        )
    }

    /** 模型测速 - 三指标（TTFT / TPS / 总耗时） */
    fun testModelSpeed(model: AiModel) {
        viewModelScope.launch {
            try {
                _snackbarMessage.value = "Замер скорости ${model.displayName}..."
                withContext(Dispatchers.IO) {
                    val provider = database.providerDao().getProviderById(model.providerId) ?: run {
                        _snackbarMessage.value = "${model.displayName}: связанный провайдер не найден"
                        return@withContext
                    }
                    val metrics = measureModel(model, provider)
                    val result = if (metrics.ttftMs < 0) {
                        "${model.displayName}: замер скорости не удался"
                    } else {
                        "${model.displayName}: TTFT=${metrics.ttftMs} мс · TPS=${"%.1f".format(metrics.tps)} · итого ${metrics.totalMs} мс"
                    }
                    _snackbarMessage.value = result
                    // ★★ 记录测速历史 ★★
                    recordSpeedHistory(model.routeKey, model.customAlias.ifBlank { model.displayName }, model.providerId, metrics)
                    // ★★ 同时探测模型能力 ★★
                    try {
                        ModelCapabilityManager.probeModel(model.modelId, provider.resolvedBaseUrl, CredentialStore.apiKeyForProvider(provider), provider.chatPath)
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                _snackbarMessage.value = "${model.displayName} — замер скорости не удался: ${e.localizedMessage ?: e.message}"
            }
        }
    }

    // Идёт ли обход замеров: кнопка на вкладке моделей на время обхода гаснет.
    private val _speedSweepRunning = MutableStateFlow(false)
    val speedSweepRunning: StateFlow<Boolean> = _speedSweepRunning.asStateFlow()

    /**
     * Замерить все включённые модели по одному разу, последовательно: параллельный
     * обстрел исказил бы TTFT. Результаты уходят в историю замеров — строки на
     * вкладке моделей обновляются сами, а свежие скорости и есть основание для
     * порядка провайдеров внутри группы.
     */
    fun measureAllModelSpeeds() {
        if (_speedSweepRunning.value) return
        viewModelScope.launch {
            _speedSweepRunning.value = true
            try {
                withContext(Dispatchers.IO) {
                    val models = database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
                    if (models.isEmpty()) {
                        _snackbarMessage.value = "Нет включённых моделей для замера"
                        return@withContext
                    }
                    val providers = database.providerDao().getAllProvidersOnce().associateBy { it.id }
                    var ok = 0
                    var failed = 0
                    models.forEachIndexed { index, model ->
                        val name = model.customAlias.ifBlank { model.displayName }
                        _snackbarMessage.value = "Замер ${index + 1} из ${models.size}: $name"
                        val provider = providers[model.providerId]
                        if (provider == null || !provider.isEnabled) {
                            failed++
                            return@forEachIndexed
                        }
                        val metrics = try {
                            measureModel(model, provider)
                        } catch (_: Exception) {
                            failed++
                            return@forEachIndexed
                        }
                        recordSpeedHistory(model.routeKey, name, model.providerId, metrics)
                        if (metrics.ttftMs >= 0) ok++ else failed++
                    }
                    _snackbarMessage.value = "Замеры завершены: $ok успешно, $failed не удалось"
                }
            } finally {
                _speedSweepRunning.value = false
            }
        }
    }

    /** 删除模型 */
    fun deleteModel(model: com.aigate.router.data.model.AiModel) {
        viewModelScope.launch {
            try {
                database.aiModelDao().delete(model)
                _snackbarMessage.value = "Модель удалена: ${model.displayName}"
            } catch (e: Exception) {
                _snackbarMessage.value = "Не удалось удалить: ${e.message}"
            }
        }
    }

    // ========== 批量测速 ==========
    private val _batchTesting = MutableStateFlow(false)
    val batchTesting: StateFlow<Boolean> = _batchTesting.asStateFlow()

    private val _batchTestingAutoClose = MutableStateFlow(false)
    val batchTestingAutoClose: StateFlow<Boolean> = _batchTestingAutoClose.asStateFlow()

    fun setBatchTestingAutoClose(enabled: Boolean) {
        _batchTestingAutoClose.value = enabled
    }

    /** 批量测速所有模型，通过的自动开启（顺序一个一个测） */
    fun batchTestAllModels() {
        viewModelScope.launch {
            if (_batchTesting.value) return@launch
            _batchTesting.value = true
            try {
                val allModels = database.aiModelDao().getAllModelsOnce()
                var passed = 0
                var failed = 0
                for ((i, model) in allModels.withIndex()) {
                    _snackbarMessage.value = "Тест [${i+1}/${allModels.size}] ${model.displayName}..."
                    // 逐个测速，每个都在 IO 线程执行
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            val provider = database.providerDao().getProviderById(model.providerId)
                            if (provider == null) return@withContext false
                            val client = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            val body = """{"model":"${model.modelId}","messages":[{"role":"user","content":"hi"}],"max_tokens":1}"""
                                .toByteArray().toRequestBody("application/json".toMediaType())
                            val chatPath = provider.chatPath?.let { if (it.startsWith("/")) it else "/$it" } ?: "/v1/chat/completions"
                            val request = okhttp3.Request.Builder()
                                .url("${provider.resolvedBaseUrl}$chatPath").post(body)
                                .apply { CredentialStore.apiKeyForProvider(provider)?.let { header("Authorization", "Bearer $it") } }
                                .build()
                            val response = client.newCall(request).execute()
                            val success = response.isSuccessful
                            response.close()
                            success
                        } catch (_: Exception) { false }
                    }
                    if (ok) {
                        database.aiModelDao().update(model.copy(isEnabled = true))
                        passed++
                    } else {
                        if (_batchTestingAutoClose.value) {
                            database.aiModelDao().update(model.copy(isEnabled = false))
                        }
                        failed++
                    }
                }
                _snackbarMessage.value = "Пакетный тест завершён: $passed пройдено (включены), $failed не удалось"
            } catch (e: Exception) {
                _snackbarMessage.value = "Ошибка пакетного теста: ${e.message}"
            } finally {
                _batchTesting.value = false
            }
        }
    }

    fun clearSnackbar() {
    _snackbarMessage.value = null
}

fun clearSyncResult() {
    _syncResult.value = null
}

// ★ Debug 抓包模式
    private val _debugMode = MutableStateFlow(GatewayForegroundService.getDebugMode())
    val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()

    fun toggleDebugMode() {
        val newMode = !_debugMode.value
        _debugMode.value = newMode
        GatewayForegroundService.saveDebugMode(newMode)
        _snackbarMessage.value = if (newMode) "Режим перехвата включён" else "Режим перехвата выключен"
    }
    fun getDebugLogs(): List<String> = GatewayForegroundService.getDebugLogs()
    fun clearDebugLogs() { GatewayForegroundService.clearDebugLogs() }

    // ==================== 流水线接力测速 ====================

    /** 启动流水线测速（全自动循环模式，每20秒刷新一轮） */
    fun startPipelineTest() {
        if (_pipelineRunning.value) return
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            _pipelineRunning.value = true
            _autoModelStatus.value = _autoModelStatus.value.copy(isTesting = true)
            _pipelineProgress.value = 0f
            try {
                var firstRound = true
                while (_pipelineRunning.value) {
                    val enabledList = database.aiModelDao().getEnabledModelsList().filter { it.isEnabled }
                    if (enabledList.isEmpty()) { _pipelineRunning.value = false; refreshAutoModelStatus(); return@launch }

                    // 保留旧测速结果，新模型加入等待中
                    val oldStatus = _pipelineStatus.value
                    val oldMap = oldStatus.associateBy { it.selectionKey }
                    _pipelineStatus.value = enabledList.map { model ->
                        oldMap[model.routeKey]?.copy(
                            modelName = model.customAlias.ifBlank { model.displayName },
                            providerId = model.providerId,
                            ) ?: PipelineTestItem(
                            modelId = model.modelId,
                            modelName = model.customAlias.ifBlank { model.displayName },
                            providerId = model.providerId,
                                status = "Ожидание"
                        )
                    }

                    val sortedList = if (firstRound) enabledList
                    else {
                        val speedMap = _pipelineStatus.value.associate { it.selectionKey to it.latencyMs }
                        enabledList.sortedBy { speedMap[it.routeKey] ?: Long.MAX_VALUE }
                    }

                    for (model in sortedList) {
                        if (!_pipelineRunning.value) break
                        val realIdx = _pipelineStatus.value.indexOfFirst { it.selectionKey == model.routeKey }
                        if (realIdx < 0) continue
                        if (!_pipelineRunning.value) break
                        // ★★ 更新进度 ★★
                        val testedCount = _pipelineStatus.value.count { it.outcome != TestOutcome.Pending }
                        val total = _pipelineStatus.value.size
                        _pipelineProgress.value = if (total > 0) testedCount.toFloat() / total.toFloat() else 0f

                        val cur = _pipelineStatus.value.toMutableList()
                        cur[realIdx] = cur[realIdx].copy(status = "Замер скорости...", isCurrent = true)
                        for (i in cur.indices) { if (i != realIdx) cur[i] = cur[i].copy(isCurrent = false) }
                        _pipelineStatus.value = cur

                        val provider = database.providerDao().getProviderById(model.providerId)
                        if (provider == null || !provider.isEnabled) {
                            val sk = _pipelineStatus.value.toMutableList()
                            sk[realIdx] = sk[realIdx].copy(status = "Провайдер отключён", latencyMs = Long.MAX_VALUE, isCurrent = false)
                            _pipelineStatus.value = sk; continue
                        }

                        // ★★ 三指标测速（TTFT / TPS / 总耗时） ★★
                        var success = false; var latency = 0L; var errorMsg = ""
                        var ttft = 0L; var tps = 0.0; var tokens = 0
                        try {
                            withContext(Dispatchers.IO) {
                                val metrics = measureModel(model, provider)
                                latency = metrics.totalMs
                                ttft = metrics.ttftMs
                                tps = metrics.tps
                                tokens = metrics.tokenCount
                                if (metrics.ttftMs >= 0) {
                                    success = true
                                    if (tokens == 0) errorMsg = "· нет вывода"
                                } else {
                                    errorMsg = "замер скорости не удался"
                                }
                            }
                        } catch (e: Exception) { errorMsg = e.message?.take(60) ?: "тайм-аут" }

                        // ★★ 记录测速历史到数据库（用于趋势图）★★
                        if (ttft != 0L || tps != 0.0) {
                            val modelName = model.customAlias.ifBlank { model.displayName }
                            recordSpeedHistory(model.routeKey, modelName, model.providerId,
                                SpeedMetrics(ttftMs = ttft, tps = tps, totalMs = latency, tokenCount = tokens, measuredAt = System.currentTimeMillis()))
                        }

                        // ★★ 用完返回再发探针检测模型能力（异步，不影响排序）★★
                                if (success) {
                                    try {
                                        ModelCapabilityManager.probeModel(
                                            model.modelId,
                                            provider.resolvedBaseUrl,
                                            CredentialStore.apiKeyForProvider(provider),
                                            provider.chatPath
                                        )
                                    } catch (_: Exception) {}
                                }

                                val rl = _pipelineStatus.value.toMutableList()
                        rl[realIdx] = rl[realIdx].copy(
                            status = if (success) {
                                "TTFT ${ttft} мс · TPS ${"%.0f".format(tps)} · ${latency} мс"
                            } else {
                                errorMsg
                            },
                            outcome = if (success) TestOutcome.Success else TestOutcome.Failure,
                            latencyMs = if (success) latency else Long.MAX_VALUE, isCurrent = false
                        )
                        _pipelineStatus.value = rl
                    }

                    val sorted = _pipelineStatus.value.sortedBy { it.latencyMs }
                    _pipelineStatus.value = sorted
                    com.aigate.router.gateway.GatewayScheduler.pipelineSortedModelKeys = sorted.map { it.selectionKey }
                    // ★ 保存测速结果缓存
                    savePipelineCache(sorted)
                    firstRound = false
                    // ★★ 测速完成一轮 → 关闭运行状态，开启倒计时 ★★
                    _pipelineRunning.value = false
                    val intervalMinutes = GatewayForegroundService.getPipelineInterval()
                    val totalSeconds = (intervalMinutes * 60).coerceAtLeast(10) // 最少10秒
                    _pipelineCountdown.value = totalSeconds
                    // ★★ 倒计时循环，每秒更新一次，归零自动重启测速 ★★
                    // ★ 修复：使用独立try-catch保护倒计时，防止异常中断循环 ★
                    try {
                        while (_pipelineCountdown.value > 0) {
                            kotlinx.coroutines.delay(1000)
                            _pipelineCountdown.value--
                        }
                    } catch (_: Exception) {
                        // 倒计时被取消，不重启测速
                        _pipelineCountdown.value = 0
                        refreshAutoModelStatus()
                        return@launch
                    }
                    _pipelineCountdown.value = 0
                    _pipelineRunning.value = true
                }
            } catch (_: Exception) {
                // 外层异常：测速异常中断，仍尝试重启
                _pipelineRunning.value = false
                _pipelineCountdown.value = 0
                refreshAutoModelStatus()
                // 异常后延迟10秒自动重启
                kotlinx.coroutines.delay(10000)
                if (!_pipelineRunning.value) {
                    _pipelineRunning.value = true
                }
            }
            if (_pipelineRunning.value) {
                // 正常循环继续
            } else {
                _pipelineRunning.value = false
                _pipelineCountdown.value = 0
                refreshAutoModelStatus()
            }
        }
    }

    fun stopPipelineTest() {
        _pipelineRunning.value = false
        _pipelineCountdown.value = 0
        pipelineJob?.cancel()
        refreshAutoModelStatus()
    }

    /** ★★ 刷新 auto 虚拟模型状态（基于测速排行）★★ */
    private fun refreshAutoModelStatus() {
        val sorted = com.aigate.router.gateway.GatewayScheduler.pipelineSortedModelKeys
        if (sorted.isNotEmpty()) {
            val bestId = sorted.first()
            val bestModel = enabledModels.value.findByRouteKey(bestId)
            val bestMetrics = com.aigate.router.gateway.GatewayScheduler.healthCache[bestId]
            _autoModelStatus.value = AutoModelStatus(
                available = true,
                bestModelId = bestModel?.modelId ?: ModelRouteKey.modelIdOf(bestId),
                bestModelName = bestModel?.displayName ?: ModelRouteKey.display(bestId),
                bestTtft = bestMetrics?.latencyMs ?: 0,
                lastUpdated = System.currentTimeMillis(),
                isTesting = false,
            )
        } else {
            _autoModelStatus.value = AutoModelStatus(
                available = false,
                lastUpdated = System.currentTimeMillis(),
                isTesting = _pipelineRunning.value,
            )
        }
    }

    // ★ 自动故障转移
    private val _autoFailover = MutableStateFlow(GatewayForegroundService.getAutoFailover())
    val autoFailover: StateFlow<Boolean> = _autoFailover.asStateFlow()

    fun toggleAutoFailover() {
        val newMode = !_autoFailover.value
        _autoFailover.value = newMode
        GatewayForegroundService.saveAutoFailover(newMode)
        // ★ 联动：故障转移开启 → 自动开启模型接力测速；关闭 → 关闭接力测速
        if (newMode) {
            startPipelineTest()
        } else {
            stopPipelineTest()
        }
        _snackbarMessage.value = if (newMode) "Автопереключение при сбое включено" else "Автопереключение при сбое выключено"
    }

    // ★ 自动化切换（auto）独立开关
    private val _autoModelEnabled = MutableStateFlow(GatewayForegroundService.getAutoModelEnabled())
    val autoModelEnabled: StateFlow<Boolean> = _autoModelEnabled.asStateFlow()

    fun toggleAutoModel() {
        val newMode = !_autoModelEnabled.value
        _autoModelEnabled.value = newMode
        GatewayForegroundService.saveAutoModelEnabled(newMode)
        if (newMode) {
            // 开启自动化切换时，清除强制切换，回到自动排行
            GatewayForegroundService.saveForcedModel("")
        }
        _snackbarMessage.value = if (newMode) "Автопереключение включено" else "Автопереключение выключено"
    }

    // ★ 手动强制切换模型（点排行榜上的模型）
    // 格式: "providerId::modelId" 精确匹配，避免同名模型混淆
    private val _forcedModelKey = MutableStateFlow(GatewayForegroundService.getForcedModel())
    val forcedModelKey: StateFlow<String> = _forcedModelKey.asStateFlow()

    fun forceModel(modelId: String, providerId: Long, rankingId: Int? = null) {
        val modelKey = ModelRouteKey.encode(providerId, modelId)
        if (_forcedModelKey.value == modelKey) {
            _forcedModelKey.value = ""
            GatewayForegroundService.saveForcedModel("")
            _snackbarMessage.value = "Принудительное переключение отменено, возврат к авторанжированию"
        } else {
            _forcedModelKey.value = modelKey
            GatewayForegroundService.saveForcedModel(modelKey)
            val item = _pipelineStatus.value.find { it.selectionKey == modelKey }
            val rankPrefix = rankingId?.let { "#$it · " }.orEmpty()
            _snackbarMessage.value = "Выбрано: ${rankPrefix}P$providerId · ${item?.modelName ?: modelId}"
        }
    }

    fun clearForcedModel() {
        _forcedModelKey.value = ""
        GatewayForegroundService.saveForcedModel("")
    }

    // ★★ 实时会话列表（从全局读）★★
    var liveSessions: List<LiveSession>
        get() = GatewayForegroundService.liveSessions
        private set(value) { /* 只读 */ }
    
    fun clearLiveSessions() {
        GatewayForegroundService.clearLiveSessions()
    }

    // ==================== Локальные модели ====================

    /**
     * Состояние поиска по каталогу.
     *
     * [Idle] отделён от пустого [Results] намеренно: пока запрос не введён,
     * экран не должен показывать ни «ничего не найдено», ни пустой список —
     * раздела результатов там просто нет.
     */
    sealed interface CatalogSearchState {
        object Idle : CatalogSearchState
        object Loading : CatalogSearchState
        data class Results(val result: ModelCatalogRepository.SearchResult) : CatalogSearchState
        data class Error(val message: String) : CatalogSearchState
    }

    private val localModelDao = database.localModelDao()

    /** Контекст приложения: репозиториям локальных моделей он нужен в каждом вызове. */
    private val appContext: Context get() = GatewayApplication.getInstance()

    private val _catalogSearch = MutableStateFlow<CatalogSearchState>(CatalogSearchState.Idle)
    val catalogSearch: StateFlow<CatalogSearchState> = _catalogSearch.asStateFlow()

    /** Предыдущий поиск отменяется новым: выдача устаревшего запроса не нужна. */
    private var catalogSearchJob: kotlinx.coroutines.Job? = null

    val localModels: StateFlow<List<LocalModel>> =
        LocalModelsRepository.observeAll(localModelDao)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _storageStats = MutableStateFlow<LocalModelsRepository.StorageStats?>(null)
    val storageStats: StateFlow<LocalModelsRepository.StorageStats?> = _storageStats.asStateFlow()

    private val _localEnginesSupported = MutableStateFlow(false)
    val localEnginesSupported: StateFlow<Boolean> = _localEnginesSupported.asStateFlow()

    init {
        // Зонд грузит классы beta-SDK и нативную библиотеку — это работа не для
        // главного потока, и делается она ровно один раз за жизнь процесса.
        viewModelScope.launch {
            _localEnginesSupported.value = withContext(Dispatchers.IO) {
                com.aigate.router.capability.DeviceSupportProbe.report(appContext).anyEngineSupported
            }
        }
        refreshStorageStats()
    }

    /** Поиск по каталогу. Пустой запрос возвращает экран в исходное состояние. */
    fun searchCatalog(
        query: String,
        source: ModelCatalogRepository.CatalogSource,
        litertOnly: Boolean = false,
    ) {
        val needle = query.trim()
        catalogSearchJob?.cancel()
        if (needle.isEmpty()) {
            _catalogSearch.value = CatalogSearchState.Idle
            return
        }
        catalogSearchJob = viewModelScope.launch {
            _catalogSearch.value = CatalogSearchState.Loading
            _catalogSearch.value = try {
                CatalogSearchState.Results(
                    ModelCatalogRepository.search(appContext, needle, source, litertOnly)
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Отмена — это новый запрос пользователя, а не сбой каталога.
                throw cancelled
            } catch (e: Exception) {
                CatalogSearchState.Error(e.message ?: "Каталог сейчас недоступен")
            }
        }
    }

    fun downloadModel(
        entry: ModelCatalogRepository.CatalogEntry,
        variant: ModelCatalogRepository.CatalogVariant,
    ) {
        viewModelScope.launch {
            LocalModelsRepository.startDownload(appContext, localModelDao, entry, variant)
                .onSuccess {
                    _snackbarMessage.value =
                        "«${ModelCatalogRepository.variantDisplayName(entry, variant)}» в очереди на загрузку"
                    refreshStorageStats()
                }
                .onFailure { _snackbarMessage.value = it.message ?: "Загрузку начать не удалось" }
        }
    }

    fun pauseDownload(id: Long) {
        viewModelScope.launch { LocalModelsRepository.pause(appContext, localModelDao, id) }
    }

    fun resumeDownload(id: Long) {
        viewModelScope.launch { LocalModelsRepository.resume(appContext, localModelDao, id) }
    }

    fun cancelDownload(id: Long) {
        LocalModelsRepository.cancel(appContext, localModelDao, id)
    }

    fun deleteLocalModel(model: LocalModel) {
        viewModelScope.launch {
            // Удаление ходит по файловой системе, поэтому уходит с главного потока.
            runCatching {
                withContext(Dispatchers.IO) {
                    LocalModelsRepository.delete(appContext, localModelDao, model)
                }
            }
                .onSuccess {
                    _snackbarMessage.value = "Модель «${model.displayName}» удалена"
                    refreshStorageStats()
                }
                .onFailure { _snackbarMessage.value = "Не удалось удалить: ${it.message}" }
        }
    }

    /** Пересчёт занятого места. Ошибка оставляет null — экран покажет прочерк. */
    fun refreshStorageStats() {
        viewModelScope.launch {
            // Занятое считается обходом каталога моделей — тоже дисковая работа.
            _storageStats.value = runCatching {
                withContext(Dispatchers.IO) {
                    LocalModelsRepository.storageStats(appContext, localModelDao)
                }
            }.getOrNull()
        }
    }

    // ========== Factory ==========

    class Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = AppDatabase.getInstance(GatewayApplication.getInstance())
            return GatewayViewModel(db) as T
        }
    }
}
