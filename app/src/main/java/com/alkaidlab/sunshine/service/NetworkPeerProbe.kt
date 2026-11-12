package com.alkaidlab.sunshine.service

/**
 * 对端探测 + ICMP ping 探针（从 SunshineRuntimeManager 抽出的纯函数，无状态）。
 *
 * - [detectConnectedPeerIps]：从 /proc/net/tcp(tcp6) 反查 sunshine 服务端口
 *   （47984/47989/48010/47998）的已连接对端 IP；
 * - [measurePingRttMs]：对探测到的对端做单次 ICMP ping 测 RTT（纯探测，不计入
 *   视频传输/编码延迟，调用方注释见 SunshineRuntimeManager.startTelemetryLoop）。
 *
 * 函数体与抽出前逐字一致。探测结果的上层状态
 * （lastNetworkLatencyMs / lastNetworkProbeIp / lastDiscoveredClient）仍由
 * SunshineRuntimeManager 维护，留待遥测循环拆分（路线图 #5 StreamTelemetry）时再编排。
 */
internal object NetworkPeerProbe {
    fun detectConnectedPeerIps(): List<String> {
        val result = linkedSetOf<String>()
        val sunshinePortsHex = setOf("BB70", "BB75", "BB8A", "BB7E") // 47984, 47989, 48010, 47998
        listOf("/proc/net/tcp6", "/proc/net/tcp").forEach { path ->
            try {
                java.io.File(path).forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val local = parts[1].uppercase()
                        val remote = parts[2].uppercase()
                        val localPort = local.substringAfter(":", "")
                        if (localPort in sunshinePortsHex) {
                            val remHex = remote.substringBefore(":", "")
                            val ip = decodeProcHexIp(remHex)
                            if (ip != null && ip != "127.0.0.1" && ip != "0.0.0.0" && !ip.startsWith("0:")) {
                                result.add(ip)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        return result.toList()
    }

    fun decodeProcHexIp(hex: String): String? {
        return try {
            if (hex.length == 8) {
                val b0 = hex.substring(6, 8).toInt(16)
                val b1 = hex.substring(4, 6).toInt(16)
                val b2 = hex.substring(2, 4).toInt(16)
                val b3 = hex.substring(0, 2).toInt(16)
                if (b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0) null else "$b0.$b1.$b2.$b3"
            } else if (hex.length == 32 && hex.startsWith("0000000000000000FFFF0000")) {
                decodeProcHexIp(hex.substring(24, 32))
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    fun measurePingRttMs(ip: String, timeRegex: Regex): Double? {
        var proc: java.lang.Process? = null
        return try {
            proc = ProcessBuilder("ping", "-c", "1", "-W", "1", ip).redirectErrorStream(true).start()
            if (!proc.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)) return null
            timeRegex.find(proc.inputStream.bufferedReader().readText())?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            proc?.destroy()
        }
    }
}
