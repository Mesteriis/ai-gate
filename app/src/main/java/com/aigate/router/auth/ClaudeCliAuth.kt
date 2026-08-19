package com.aigate.router.auth

/**
 * Claude Code: вход подпиской Claude через браузер, как у Codex.
 *
 * Значения не выдуманы, а сняты с установленного Claude Code CLI (его сборка
 * содержит конфигурацию флоу в открытом виде):
 *  - `client_id` — идентификатор клиента Claude Code;
 *  - адрес авторизации подписки, адрес токена, скоупы, обязательный параметр
 *    `code=true` и бета-заголовок `oauth-2025-04-20`;
 *  - редирект — loopback `http://localhost:<порт>/callback` с произвольным
 *    портом (RFC 8252), клиент публичный: PKCE S256, без секрета;
 *  - обмен кода и обновление токена идут ТЕЛОМ JSON, а не form-urlencoded.
 *
 * Эксперимент и ToS: обращение к подписке Claude сторонним клиентом может
 * нарушать условия Anthropic. Аутентификацию выполняет сам владелец своей
 * учётной записью, ответственность за использование — на нём.
 */
object ClaudeCliAuth {

    const val DISPLAY_NAME = "Claude Code"
    const val PROVIDER_TYPE = "claude-cli"

    /** client_id клиента Claude Code. */
    const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

    /** Инференс идёт в обычный API Anthropic, но с OAuth-токеном подписки. */
    const val DEFAULT_BASE_URL = "https://api.anthropic.com"
    const val CHAT_PATH = "/v1/messages"

    /** Заголовки, без которых Anthropic не принимает OAuth-токен подписки. */
    const val BETA_HEADER = "anthropic-beta"
    const val BETA_OAUTH = "oauth-2025-04-20"
    const val VERSION_HEADER = "anthropic-version"
    const val VERSION = "2023-06-01"

    /**
     * Как представляется клиент подписки.
     *
     * Крупные модели (Opus, Sonnet) подписка отдаёт только клиенту Claude Code:
     * без этой идентичности они отвечают 429 `rate_limit_error` с пустым
     * сообщением и БЕЗ заголовков лимита — то есть отказ не про квоту. Проверено
     * на устройстве при 31 % сессии и 33 % недели: Haiku отвечал, остальные нет.
     *
     * Значения сняты с установленного клиента: заголовки `x-app`, `User-Agent`,
     * `X-Claude-Code-Session-Id` и обязательный первый системный блок запроса.
     *
     * Владелец подписки включил это сознательно: обращение сторонним клиентом,
     * который представляется Claude Code, может расходиться с условиями
     * Anthropic. Решение и ответственность — владельца учётной записи.
     */
    const val CLIENT_VERSION = "2.1.216"
    const val USER_AGENT = "claude-cli/$CLIENT_VERSION (external, cli)"
    const val APP_HEADER = "x-app"
    const val APP = "cli"
    const val SESSION_HEADER = "X-Claude-Code-Session-Id"
    const val IDENTITY_PROMPT = "You are Claude Code, Anthropic's official CLI for Claude."

    /** Контекст моделей Claude — 200k токенов. */
    const val CONTEXT_WINDOW = 200_000

    /**
     * Запасной список моделей: используется, только если `GET /v1/models` не
     * отдал каталог (например токен подписки не даёт доступа к этому пути).
     * Идентификаторы и подписи взяты из установленного Claude Code CLI, а не
     * придуманы; каталог сервера всегда важнее этого списка.
     */
    val FALLBACK_MODELS: List<Pair<String, String>> = listOf(
        "claude-opus-4-8" to "Opus 4.8",
        "claude-opus-4-7" to "Opus 4.7",
        "claude-opus-4-6" to "Opus 4.6",
        "claude-sonnet-5" to "Sonnet 5",
        "claude-sonnet-4-6" to "Sonnet 4.6",
        "claude-haiku-4-5" to "Haiku 4.5",
    )

    val config = OAuthFlowConfig(
        providerType = PROVIDER_TYPE,
        // Вход именно подпиской Claude (не консольным аккаунтом API).
        authUrl = "https://claude.com/cai/oauth/authorize",
        tokenUrl = "https://platform.claude.com/v1/oauth/token",
        clientId = CLIENT_ID,
        // Просим ровно то, что нужно шлюзу: выполнять запросы и узнать аккаунт.
        // Права на создание ключей организации (`org:create_api_key`) не берём.
        scopes = listOf("user:inference", "user:profile"),
        // Порт эфемерный — так же, как у самого CLI: фиксированный конфликтовал бы.
        fixedPort = null,
        redirectPath = "/callback",
        // Без этого параметра страница входа не отдаёт код.
        extraAuthParams = mapOf("code" to "true"),
        // Параметры Google (`access_type`, `prompt`) здесь лишние.
        requestOfflineAccess = false,
        tokenRequestJson = true,
        sendStateInTokenRequest = true,
    )
}
