package com.aigate.router.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Список моделей Codex приходит С СЕРВЕРА, а не задаётся в приложении.
 *
 * Формат подтверждён по реальному Codex CLI: ответ — объект `{"models":[ … ]}`,
 * каждый элемент содержит `slug`, `display_name`, `description`, `visibility`
 * ("list" — показывать, "hide" — скрытая служебная), `supported_in_api`
 * (false — недоступна для сторонних клиентов), `priority` (порядок в списке),
 * `context_window` / `max_context_window`. Ответ отдаётся с ETag, поэтому
 * повторный запрос выполняется условно (`If-None-Match`) и на 304 список не
 * трогаем.
 *
 * Публичного документированного пути нет, поэтому перебираем кандидатов и
 * запоминаем сработавший. Ничего не выдумываем: если сервер список не отдал,
 * возвращаем null, и вызывающий оставляет прежние модели.
 */
object CodexModelsApi {

    private const val TAG = "CodexModelsApi"

    /** Одна модель в том виде, в каком её отдаёт сервер. */
    data class RemoteModel(
        val slug: String,
        val displayName: String,
        val contextWindow: Int?,
        val priority: Int,
    )

    data class Result(
        val models: List<RemoteModel>,
        val etag: String?,
        /** Путь, который сработал — чтобы не перебирать кандидатов каждый раз. */
        val endpoint: String,
        /** true, если сервер ответил 304 и список менять не нужно. */
        val notModified: Boolean = false,
    )

    /**
     * Версия клиента: бэкенд отвечает 400 «Field required: client_version»,
     * если параметра нет. Значение — версия протокола Codex CLI, от которой
     * зависит формат ответа со списком моделей.
     */
    const val CLIENT_VERSION = "0.148.0"

    /**
     * Кандидаты пути списка моделей. Порядок — от самого вероятного:
     * базовый URL сессии Codex уже указывает на `…/backend-api/codex`.
     */
    private val CANDIDATE_PATHS = listOf(
        "/models",
        "/api/codex/models",
        "/v1/models",
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Запросить список моделей.
     *
     * @param baseUrl база сессии (например `https://chatgpt.com/backend-api/codex`)
     * @param token   access token сессии
     * @param accountId идентификатор аккаунта ChatGPT (заголовок `ChatGPT-Account-ID`)
     * @param knownEtag ETag предыдущего ответа — для условного запроса
     * @param knownEndpoint ранее сработавший путь; проверяется первым
     */
    suspend fun fetch(
        baseUrl: String,
        token: String,
        accountId: String?,
        knownEtag: String? = null,
        knownEndpoint: String? = null,
    ): Result? = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext null
        val base = baseUrl.trimEnd('/')
        val paths = buildList {
            knownEndpoint?.takeIf { it.isNotBlank() }?.let { add(it) }
            CANDIDATE_PATHS.forEach { if (it != knownEndpoint) add(it) }
        }
        for (path in paths) {
            val url = base + path + "?client_version=" + CLIENT_VERSION
            val outcome = runCatching { request(url, token, accountId, knownEtag, path) }
                .onFailure { Log.w(TAG, "models request failed for $path: ${it.message}") }
                .getOrNull()
            if (outcome != null) return@withContext outcome
        }
        null
    }

    private fun request(
        url: String,
        token: String,
        accountId: String?,
        knownEtag: String?,
        path: String,
    ): Result? {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            // Те же заголовки, что посылает CLI: без них бэкенд отвечает 4xx.
            .header("User-Agent", CodexHeaders.USER_AGENT)
            .header("originator", CodexHeaders.ORIGINATOR)
        accountId?.takeIf { it.isNotBlank() }?.let { builder.header(CodexHeaders.ACCOUNT_ID, it) }
        knownEtag?.takeIf { it.isNotBlank() }?.let { builder.header("If-None-Match", it) }

        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 304) {
                return Result(models = emptyList(), etag = knownEtag, endpoint = path, notModified = true)
            }
            if (!resp.isSuccessful) {
                // Тело ошибки говорит, чего не хватает запросу (например версии
                // клиента). Секретов в нём нет — токен уходит только в заголовке.
                val err = runCatching { resp.body?.string()?.take(300) }.getOrNull()
                Log.w(TAG, "models $path → HTTP ${resp.code} $err")
                return null
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            val models = parse(body)
            if (models.isEmpty()) {
                Log.w(TAG, "models $path → 200, но список пуст или формат неизвестен")
                return null
            }
            return Result(models = models, etag = resp.header("ETag"), endpoint = path)
        }
    }

    /**
     * Разбор ответа. Берём только то, что реально доступно сторонним клиентам:
     * `visibility == "list"` и `supported_in_api == true`. Служебные модели
     * (например авто-ревью) в список провайдера не попадают.
     */
    internal fun parse(body: String): List<RemoteModel> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("models") ?: root.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<RemoteModel>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val slug = m.optString("slug").ifBlank { m.optString("id") }
            if (slug.isBlank()) continue
            val visibility = m.optString("visibility", "list")
            if (visibility.isNotBlank() && !visibility.equals("list", ignoreCase = true)) continue
            if (m.has("supported_in_api") && !m.optBoolean("supported_in_api", true)) continue
            val ctx = m.optInt("context_window", 0).takeIf { it > 0 }
                ?: m.optInt("max_context_window", 0).takeIf { it > 0 }
            out += RemoteModel(
                slug = slug,
                displayName = m.optString("display_name").ifBlank { slug },
                contextWindow = ctx,
                priority = m.optInt("priority", Int.MAX_VALUE),
            )
        }
        return out.sortedBy { it.priority }
    }
}

/** Заголовки, которые бэкенд Codex требует от клиента (сверено с Codex CLI). */
object CodexHeaders {
    const val ACCOUNT_ID = "ChatGPT-Account-ID"
    const val ORIGINATOR = "codex_cli_rs"
    const val USER_AGENT = "codex_cli_rs/0.0 (AiGate)"
    const val OPENAI_BETA = "OpenAI-Beta"
    const val OPENAI_BETA_RESPONSES = "responses=experimental"
    const val SESSION_ID = "session_id"
}
