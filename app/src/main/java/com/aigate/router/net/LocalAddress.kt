package com.aigate.router.net

import java.net.NetworkInterface

/**
 * Адрес шлюза в локальной сети.
 *
 * Раньше брался первый не-loopback адрес любого интерфейса, из-за чего в URL
 * попадал адрес мобильной сети или VPN-туннеля — недостижимый для других
 * устройств в той же Wi-Fi сети.
 */
object LocalAddress {

    data class Iface(
        val name: String,
        val addresses: List<String>,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val isPointToPoint: Boolean,
    )

    /** Приоритет: Wi-Fi, затем Ethernet, затем всё остальное. */
    private fun rank(name: String): Int = when {
        name.startsWith("wlan") || name.startsWith("ap") -> 0
        name.startsWith("eth") || name.startsWith("en") -> 1
        else -> 2
    }

    fun pick(candidates: List<Iface>): String? = candidates
        .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
        .sortedBy { rank(it.name) }
        .firstNotNullOfOrNull { iface -> iface.addresses.firstOrNull { isIpv4(it) } }

    private fun isIpv4(address: String): Boolean =
        address.count { it == '.' } == 3 && !address.contains(':')

    /** Реальные интерфейсы устройства. null — подходящей сети нет. */
    fun current(): String? = runCatching {
        pick(
            NetworkInterface.getNetworkInterfaces().asSequence().map { ni ->
                Iface(
                    name = ni.name.orEmpty(),
                    addresses = ni.inetAddresses.asSequence().mapNotNull { it.hostAddress }.toList(),
                    isUp = ni.isUp,
                    isLoopback = ni.isLoopback,
                    isPointToPoint = ni.isPointToPoint,
                )
            }.toList()
        )
    }.getOrNull()
}
