package com.alkaidlab.sunshine.service

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Network interface classification for LAN / VPN / IPv6 detection.
 *
 * Extracted verbatim from `SunshineService.refreshNetworkAddresses`; the service keeps its public
 * entry point and companion state and only delegates. Classification rules are unchanged:
 * an interface whose name starts with tun/tailscale/wg/utun/ppp — or any interface carrying a
 * 100.64.0.0/10 Tailscale CGNAT address — counts as VPN; a "wlan" interface wins the LAN slot;
 * a global 2xxx:/3xxx: IPv6 address is preferred over other valid IPv6 addresses.
 */
internal class NetworkAddressResolver(private val host: Host) {

    interface Host {
        fun logNetworkError(message: String?)
        fun updateCurrentAddresses(lan: String, vpn: String?, ipv6: String?)
    }

    data class NetworkSnapshot(
        val lanIp: String,
        val vpnIp: String?,
        val ipv6: String?
    )

    /** Mutable classification state; kept separate so the per-address rules stay pure and testable. */
    internal class AddressAccumulator {
        var lan: String? = null
        var vpn: String? = null
        var ipv6: String? = null

        fun snapshot(): NetworkSnapshot = NetworkSnapshot(lan ?: "127.0.0.1", vpn, ipv6)
    }

    companion object {
        /** True for Tailscale's 100.64.0.0/10 carrier-grade NAT range. */
        fun isTailscaleCgnat(ip: String): Boolean {
            val parts = ip.split(".")
            if (parts.size == 4 && parts[0] == "100") {
                val second = parts[1].toIntOrNull() ?: return false
                return second in 64..127
            }
            return false
        }
    }

    /**
     * Fold one interface address into [state]. Pure function of its arguments (plus the
     * [java.net] address predicates), so it is unit-testable on the JVM without Android.
     */
    internal fun accumulate(state: AddressAccumulator, ifaceName: String, addr: InetAddress) {
        if (addr.isLoopbackAddress) return
        if (addr is Inet4Address) {
            val ip = addr.hostAddress ?: return
            val isVpnIface = ifaceName.startsWith("tun") || ifaceName.startsWith("tailscale") ||
                    ifaceName.startsWith("wg") || ifaceName.startsWith("utun") || ifaceName.startsWith("ppp")
            if (isVpnIface || isTailscaleCgnat(ip)) {
                if (state.vpn == null) state.vpn = ip
            } else {
                if (ifaceName.contains("wlan")) state.lan = ip
                else if (state.lan == null) state.lan = ip
            }
        } else if (addr is Inet6Address) {
            if (!addr.isLinkLocalAddress && !addr.isSiteLocalAddress && !addr.isMulticastAddress) {
                val ip6 = (addr.hostAddress ?: "").split("%")[0]
                if (ip6.isNotBlank() &&
                    (state.ipv6 == null || ip6.startsWith("2") || ip6.startsWith("3"))
                ) {
                    state.ipv6 = ip6
                }
            }
        }
    }

    fun refreshNetworkAddresses(): NetworkSnapshot {
        val state = AddressAccumulator()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    accumulate(state, name, addrs.nextElement())
                }
            }
        } catch (e: Exception) {
            host.logNetworkError(e.message)
        }
        val snapshot = state.snapshot()
        host.updateCurrentAddresses(snapshot.lanIp, snapshot.vpnIp, snapshot.ipv6)
        return snapshot
    }
}
