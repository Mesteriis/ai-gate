package com.aigate.router.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.net.NetworkInterface

/**
 * Локальный IPv4-адрес устройства или null, если адрес получить не удалось.
 * Возвращаем именно null (а не текст ошибки, как раньше) — чтобы «Не удалось
 * получить IP» никогда не попадал внутрь URL и в буфер обмена.
 */
fun localIpAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .flatMap { it.inetAddresses.asSequence() }
        .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
        ?.hostAddress
}.getOrNull()

/**
 * Один общий тикер вместо россыпи `while(true) { delay(...) }` по экранам:
 * значение меняется раз в [intervalMs], пока композиция активна.
 */
@Composable
fun rememberTicker(intervalMs: Long = 2_000L): State<Long> =
    produceState(initialValue = 0L, intervalMs) {
        while (true) {
            delay(intervalMs)
            value += 1
        }
    }
