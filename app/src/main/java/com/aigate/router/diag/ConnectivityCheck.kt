package com.aigate.router.diag

import com.aigate.router.auth.CodexAccount
import com.aigate.router.auth.CodexModelsApi
import com.aigate.router.data.credential.CredentialStore
import com.aigate.router.data.db.AppDatabase
import com.aigate.router.gateway.CodexUpstream
import com.aigate.router.gateway.local.LocalBackendRegistry
import com.aigate.router.network.ModelCatalogApi
import com.aigate.router.service.GatewayForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Самопроверка связи одним действием: слушает ли шлюз порт, отвечают ли
 * апстримы, жива ли сессия CLI-провайдера. Раньше при неполадке приходилось
 * гадать, где именно оборвалась цепочка.
 */
object ConnectivityCheck {

    enum class State { OK, WARN, FAIL }

    data class Step(val title: String, val state: State, val detail: String)

    /** Худшее состояние среди шагов — им подписывается результат проверки. */
    fun worst(steps: List<Step>): State = when {
        steps.any { it.state == State.FAIL } -> State.FAIL
        steps.any { it.state == State.WARN } -> State.WARN
        else -> State.OK
    }

    /** Человеческая подпись итога. */
    fun summary(steps: List<Step>): String = when (worst(steps)) {
        State.OK -> "Связь в порядке"
        State.WARN -> "Работает с замечаниями"
        State.FAIL -> "Есть неполадки"
    }

    /**
     * Срок жизни сессии: превращает остаток времени в шаг проверки. Вынесено
     * отдельно, чтобы правило проверялось тестом без сети и без базы.
     */
    fun sessionStep(providerName: String, expiresAt: Long?, now: Long): Step {
        if (expiresAt == null) {
            return Step(providerName, State.OK, "сессия без срока")
        }
        val hoursLeft = (expiresAt - now) / 3_600_000.0
        return when {
            hoursLeft <= 0 -> Step(providerName, State.FAIL, "сессия истекла, нужен повторный вход")
            hoursLeft < 24 -> Step(providerName, State.WARN, "сессия истекает менее чем через сутки")
            else -> Step(providerName, State.OK, "сессия действует")
        }
    }

    /** Полная проверка. Каждый шаг сообщает о себе сам, ошибки не глотаются молча. */
    suspend fun run(db: AppDatabase, port: Int): List<Step> = withContext(Dispatchers.IO) {
        val steps = mutableListOf<Step>()
        val now = System.currentTimeMillis()

        steps += if (GatewayForegroundService.isServiceRunning) {
            Step("Шлюз", State.OK, "слушает порт $port")
        } else {
            Step("Шлюз", State.FAIL, "остановлен: запросы не принимаются")
        }

        val providers = db.providerDao().getAllProvidersOnce().filter { it.isEnabled }
        if (providers.isEmpty()) {
            steps += Step("Провайдеры", State.FAIL, "ни один провайдер не включён")
            return@withContext steps
        }

        for (provider in providers) {
            val token = CredentialStore.apiKeyForProvider(provider)
            val credential = db.credentialDao().getByProvider(provider.id)

            if (CodexUpstream.isCodex(provider)) {
                // Сессию проверяем по сроку, доступность — запросом списка моделей.
                steps += sessionStep(provider.name, credential?.oauthExpiresAt, now)
                val models = if (token.isNullOrBlank()) null else CodexModelsApi.fetch(
                    baseUrl = provider.resolvedBaseUrl,
                    token = token,
                    accountId = CodexAccount.headerAccountId(credential?.accountId, token),
                )
                steps += if (models == null) {
                    Step(provider.name, State.FAIL, "бэкенд не ответил на запрос моделей")
                } else {
                    Step(provider.name, State.OK, "моделей доступно: ${models.models.size}")
                }
                continue
            }

            // Модели на устройстве считаются в этом же процессе: сети им не
            // нужно, и «не отвечает» было бы неправдой.
            if (LocalBackendRegistry.ownsType(provider.type)) {
                steps += Step(provider.name, State.OK, "модели на устройстве, сеть не нужна")
                continue
            }

            // Адрес провайдера вводит пользователь. Если он не сетевой, честнее
            // сказать это прямо, чем свалить всё на «не отвечает».
            if (!ModelCatalogApi.isNetworkAddress(provider.resolvedBaseUrl)) {
                steps += Step(provider.name, State.FAIL, "адрес не сетевой: ${provider.resolvedBaseUrl}")
                continue
            }

            val models = ModelCatalogApi.fetch(provider, token)
            steps += when {
                models == null -> Step(provider.name, State.FAIL, "не отвечает или ключ отклонён")
                models.isEmpty() -> Step(provider.name, State.WARN, "ответил, но список моделей пуст")
                else -> Step(provider.name, State.OK, "моделей доступно: ${models.size}")
            }
        }
        steps
    }
}
