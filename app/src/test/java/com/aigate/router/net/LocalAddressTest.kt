package com.aigate.router.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAddressTest {
    private fun iface(
        name: String,
        addr: String?,
        up: Boolean = true,
        loopback: Boolean = false,
        p2p: Boolean = false,
    ) = LocalAddress.Iface(name, listOfNotNull(addr), up, loopback, p2p)

    @Test
    fun `wifi wins over mobile and vpn`() {
        val picked = LocalAddress.pick(
            listOf(
                iface("rmnet_data0", "10.204.68.22"),
                iface("tun0", "10.8.0.2", p2p = true),
                iface("wlan0", "192.168.1.42"),
            )
        )
        assertEquals("192.168.1.42", picked)
    }

    @Test
    fun `loopback and down interfaces are ignored`() {
        assertNull(
            LocalAddress.pick(
                listOf(
                    iface("lo", "127.0.0.1", loopback = true),
                    iface("wlan0", "192.168.1.42", up = false),
                )
            )
        )
    }

    @Test
    fun `ipv6 addresses are not used for the gateway url`() {
        assertNull(LocalAddress.pick(listOf(iface("wlan0", "fe80::1"))))
    }

    @Test
    fun `ethernet is used when there is no wifi`() {
        assertEquals(
            "192.168.5.7",
            LocalAddress.pick(listOf(iface("rmnet_data0", "10.1.1.1"), iface("eth0", "192.168.5.7")))
        )
    }

    @Test
    fun `mobile address is a last resort rather than nothing`() {
        assertEquals("10.204.68.22", LocalAddress.pick(listOf(iface("rmnet_data0", "10.204.68.22"))))
    }
}
