package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class NetworkPeerProbeTest {
    @Test fun decodeProcHexIp_ipv4LittleEndian() {
        // /proc/net/tcp 中 IPv4 为小端 32 位字序：0100007F = 127.0.0.1
        assertEquals("127.0.0.1", NetworkPeerProbe.decodeProcHexIp("0100007F"))
        // C0.A8.01.64 小端存放为 6401A8C0 = 192.168.1.100
        assertEquals("192.168.1.100", NetworkPeerProbe.decodeProcHexIp("6401A8C0"))
    }

    @Test fun decodeProcHexIp_zeroAddressYieldsNull() {
        assertNull(NetworkPeerProbe.decodeProcHexIp("00000000"))
    }

    @Test fun decodeProcHexIp_ipv4MappedIpv6() {
        // ::ffff:127.0.0.1 在 /proc/net/tcp6 中以 0000000000000000FFFF0000 前缀存放
        assertEquals("127.0.0.1", NetworkPeerProbe.decodeProcHexIp("0000000000000000FFFF00000100007F"))
    }

    @Test fun decodeProcHexIp_nonMappedIpv6YieldsNull() {
        // 真 IPv6 地址（非映射前缀）当前不支持，返回 null 而非抛异常
        assertNull(NetworkPeerProbe.decodeProcHexIp("FE800000000000000000000000000001"))
    }

    @Test fun decodeProcHexIp_malformedInputYieldsNull() {
        assertNull(NetworkPeerProbe.decodeProcHexIp("ABC"))
        assertNull(NetworkPeerProbe.decodeProcHexIp("ZZZZZZZZ"))
        assertNull(NetworkPeerProbe.decodeProcHexIp(""))
    }
}
