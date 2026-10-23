package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class NetworkAddressResolverTest {

    private fun resolver() = NetworkAddressResolver(object : NetworkAddressResolver.Host {
        override fun logNetworkError(message: String?) {}
        override fun updateCurrentAddresses(lan: String, vpn: String?, ipv6: String?) {}
    })

    private fun accumulate(vararg entries: Pair<String, String>): NetworkAddressResolver.AddressAccumulator {
        val r = resolver()
        val state = NetworkAddressResolver.AddressAccumulator()
        for ((iface, ip) in entries) r.accumulate(state, iface, InetAddress.getByName(ip))
        return state
    }

    @Test fun tailscaleCgnatRangeDetection() {
        assertTrue(NetworkAddressResolver.isTailscaleCgnat("100.64.0.1"))
        assertTrue(NetworkAddressResolver.isTailscaleCgnat("100.127.255.255"))
        assertFalse(NetworkAddressResolver.isTailscaleCgnat("100.63.255.255"))
        assertFalse(NetworkAddressResolver.isTailscaleCgnat("100.128.0.1"))
        assertFalse(NetworkAddressResolver.isTailscaleCgnat("192.168.1.1"))
        assertFalse(NetworkAddressResolver.isTailscaleCgnat("100.abc.0.1"))
        assertFalse(NetworkAddressResolver.isTailscaleCgnat("100.64.0"))
        assertFalse(NetworkAddressResolver.isTailscaleCgnat(""))
    }

    @Test fun wlanInterfaceWinsLanSlotOverLaterInterfaces() {
        val s = accumulate("eth0" to "10.0.0.5", "wlan0" to "192.168.1.5", "rmnet0" to "10.1.2.3")
        assertEquals("192.168.1.5", s.snapshot().lanIp)
    }

    @Test fun firstNonWlanInterfaceFillsEmptyLanSlot() {
        val s = accumulate("eth0" to "10.0.0.5")
        assertEquals("10.0.0.5", s.snapshot().lanIp)
    }

    @Test fun loopbackAddressesAreIgnored() {
        val s = accumulate("lo" to "127.0.0.1")
        assertEquals("127.0.0.1", s.snapshot().lanIp) // default fallback, nothing detected
        assertNull(s.snapshot().vpnIp)
    }

    @Test fun tunInterfaceAddressCountsAsVpnAndKeepsTheFirstOne() {
        val s = accumulate("tun0" to "100.90.1.2", "tun0" to "100.90.1.3")
        assertEquals("100.90.1.2", s.snapshot().vpnIp)
        assertEquals("127.0.0.1", s.snapshot().lanIp) // no LAN address seen
    }

    @Test fun cgnatAddressOnNonVpnInterfaceCountsAsVpn() {
        val s = accumulate("eth0" to "100.64.0.9")
        assertEquals("100.64.0.9", s.snapshot().vpnIp)
        assertEquals("127.0.0.1", s.snapshot().lanIp)
    }

    @Test fun ipv6PrefersGlobal2xxxOverUla() {
        val s = accumulate("wlan0" to "fd00::1", "wlan0" to "2001:db8::1")
        assertTrue(s.snapshot().ipv6!!.startsWith("2001"))
    }

    @Test fun laterGlobalIpv6OverridesEarlierOne() {
        val s = accumulate("wlan0" to "2001:db8::1", "wlan0" to "2001:db8::2")
        assertTrue(s.snapshot().ipv6!!.startsWith("2001:db8"))
    }

    @Test fun nonGlobalIpv6IsKeptWhenNothingBetterArrives() {
        val s = accumulate("wlan0" to "fd00::1")
        assertTrue(s.snapshot().ipv6!!.startsWith("fd"))
    }

    @Test fun linkLocalAndMulticastIpv6AreIgnored() {
        val s = accumulate("wlan0" to "fe80::1", "wlan0" to "ff02::1")
        assertNull(s.snapshot().ipv6)
    }

    @Test fun snapshotDefaultsWhenNothingDetected() {
        val snap = NetworkAddressResolver.AddressAccumulator().snapshot()
        assertEquals("127.0.0.1", snap.lanIp)
        assertNull(snap.vpnIp)
        assertNull(snap.ipv6)
    }
}
