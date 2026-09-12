package com.homektv.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanScannerSubnetTest {

    @Test
    fun prefersPhysicalWifiOverVpnTunnel() {
        val candidates = listOf(
            LanInterfaceCandidate(
                name = "tun0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                siteLocalIpv4 = listOf("10.8.0.2"),
            ),
            LanInterfaceCandidate(
                name = "wlan0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                siteLocalIpv4 = listOf("192.168.31.45"),
            ),
        )

        val prefix = LanScanner.selectSubnetPrefix(candidates)
        assertEquals("192.168.31.", prefix)
    }

    @Test
    fun prefersPhysicalEthernetOverVirtualDockerBridge() {
        val candidates = listOf(
            LanInterfaceCandidate(
                name = "docker0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                siteLocalIpv4 = listOf("172.17.0.1"),
            ),
            LanInterfaceCandidate(
                name = "eth0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                siteLocalIpv4 = listOf("192.168.1.100"),
            ),
        )

        val prefix = LanScanner.selectSubnetPrefix(candidates)
        assertEquals("192.168.1.", prefix)
    }

    @Test
    fun ignoresDownOrLoopbackOrVirtualInterfaces() {
        val candidates = listOf(
            LanInterfaceCandidate(
                name = "lo",
                isUp = true,
                isLoopback = true,
                isVirtual = false,
                siteLocalIpv4 = listOf("127.0.0.1"),
            ),
            LanInterfaceCandidate(
                name = "wlan0",
                isUp = false,
                isLoopback = false,
                isVirtual = false,
                siteLocalIpv4 = listOf("192.168.1.10"),
            ),
            LanInterfaceCandidate(
                name = "dummy0",
                isUp = true,
                isLoopback = false,
                isVirtual = true,
                siteLocalIpv4 = listOf("192.168.2.10"),
            ),
        )

        val prefix = LanScanner.selectSubnetPrefix(candidates)
        assertNull(prefix)
    }

    @Test
    fun allowsCorporateLanOnPhysicalInterfaceWhenNoWifiAvailable() {
        val candidates = listOf(
            LanInterfaceCandidate(
                name = "eth0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                siteLocalIpv4 = listOf("10.0.1.25"),
            ),
        )

        val prefix = LanScanner.selectSubnetPrefix(candidates)
        assertEquals("10.0.1.", prefix)
    }

    @Test
    fun ignoresVpnAndProxyVirtualInterfacesLikeWireguardAndTailscale() {
        val candidates = listOf(
            LanInterfaceCandidate(
                name = "wg0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                siteLocalIpv4 = listOf("10.0.0.2"),
            ),
            LanInterfaceCandidate(
                name = "tailscale0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                siteLocalIpv4 = listOf("10.64.0.5"),
            ),
            LanInterfaceCandidate(
                name = "clash0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                siteLocalIpv4 = listOf("172.19.0.1"),
            ),
            LanInterfaceCandidate(
                name = "zt0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                siteLocalIpv4 = listOf("10.147.20.1"),
            ),
        )

        val prefix = LanScanner.selectSubnetPrefix(candidates)
        assertNull(prefix)
    }
}
