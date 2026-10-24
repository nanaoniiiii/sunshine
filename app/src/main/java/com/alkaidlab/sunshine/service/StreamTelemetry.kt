package com.alkaidlab.sunshine.service

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import com.alkaidlab.sunshine.data.NativeVideoSession
import com.alkaidlab.sunshine.data.RuntimeSession
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 遥测循环（约 1Hz 后台线程 "Sunshine-Telemetry"）。
 *
 * God object 拆分路线图 #5：从 SunshineRuntimeManager 抽出的 startTelemetryLoop/stopTelemetryLoop。
 * 线程体逐字搬运，仅把对 manager 私有状态/方法的访问收敛到 [Host] 接口；
 * 所有状态仍由 manager 持有（onStreamStarted 的重置、onClientDiscovered 的写入、
 * getPerfSnapshot 的读取都在 manager 侧原样保留），因此多代码路径的语义与抽出前完全一致。
 *
 * 线程生命周期与之前相同：epoch 计数 + interrupt 双保险；daemon 线程；线程名不变。
 */
internal class StreamTelemetry(private val host: Host) {

    internal interface Host {
        fun isStreamingActive(): Boolean
        fun frameDispatcher(): SunshineFrameDispatcher
        fun setLastFps(v: Double)
        fun setLastEncodeTimeMs(v: Double)
        fun setLastBitrate(v: Int)
        fun setLastDiscoveredClient(ip: String)
        fun setLastNetworkLatencyMs(rtt: Double)
        fun setLastNetworkProbeIp(ip: String)
        fun setLastNetworkSampleMs(ms: Long)
        fun activeSessions(): CopyOnWriteArrayList<RuntimeSession>
        fun nativeVideoSessions(): Map<Long, NativeVideoSession>
        fun detectConnectedPeerIps(): List<String>
        fun measurePingRttMs(ip: String, timeRegex: Regex): Double?
        fun withSessionLock(block: () -> Unit)
        fun publishSessions(sessions: List<RuntimeSession>)
        fun publishPerf()
        fun updateDisplayDimensionsIfAutoRotate()
    }

    private var telemetryThread: Thread? = null
    @Volatile private var telemetryEpoch = 0L
    private var prevTxBytes: Long = -1L
    private var prevTxTimeMs: Long = 0L

    fun start() {
        if (telemetryThread?.isAlive == true) return
        prevTxBytes = TrafficStats.getUidTxBytes(Process.myUid())
        prevTxTimeMs = SystemClock.elapsedRealtime()

        val epoch = ++telemetryEpoch
        telemetryThread = Thread({
            val timeRegex = Regex("time=([0-9.]+)")
            while (host.isStreamingActive() && telemetryEpoch == epoch && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(1000)
                    if (!host.isStreamingActive() || telemetryEpoch != epoch) break

                    // 1. Sample real-time FPS & GPU->Hardware Encoder surface latency
                    val (measuredFps, measuredEncodeMs) = host.frameDispatcher().sampleTelemetry()
                    host.setLastFps(measuredFps)
                    host.setLastEncodeTimeMs(measuredEncodeMs)

                    // 2. Sample real-time process network TX bitrate via TrafficStats
                    val nowTx = TrafficStats.getUidTxBytes(Process.myUid())
                    val nowMs = SystemClock.elapsedRealtime()
                    if (prevTxBytes >= 0 && nowTx >= prevTxBytes && nowMs > prevTxTimeMs) {
                        val deltaBytes = nowTx - prevTxBytes
                        val elapsedSec = (nowMs - prevTxTimeMs) / 1000.0
                        val kbps = ((deltaBytes * 8.0) / elapsedSec / 1000.0).toInt()
                        host.setLastBitrate(kbps)
                    }
                    prevTxBytes = nowTx
                    prevTxTimeMs = nowMs

                    // 3. Auto-detect connected peer IPs from /proc/net/tcp & /proc/net/tcp6
                    val peerIps = host.detectConnectedPeerIps()
                    if (peerIps.isNotEmpty()) {
                        host.setLastDiscoveredClient(peerIps.last())
                    }

                    // ICMP is a peer probe, not a video transport/encode latency measurement.
                    val probeIp = host.activeSessions().lastOrNull()?.clientIp?.takeIf { it != "127.0.0.1" }
                        ?: peerIps.lastOrNull()
                    if (probeIp != null && probeIp != "127.0.0.1") {
                        val rtt = host.measurePingRttMs(probeIp, timeRegex)
                        if (rtt != null && rtt > 0.0) {
                            host.setLastNetworkLatencyMs(rtt)
                            host.setLastNetworkProbeIp(probeIp)
                            host.setLastNetworkSampleMs(SystemClock.elapsedRealtime())
                        }
                    }

                    if (telemetryEpoch != epoch || Thread.currentThread().isInterrupted) break
                    val nativeVideo = host.nativeVideoSessions()
                    host.withSessionLock {
                        val sessions = host.activeSessions()
                        if (sessions.isNotEmpty()) {
                            var changed = false
                            val updated = sessions.mapIndexed { idx, s ->
                                val resolvedIp = if (s.clientIp == "127.0.0.1" && peerIps.isNotEmpty()) {
                                    peerIps.getOrElse(idx) { peerIps.last() }
                                } else s.clientIp
                                val resolvedName = if (s.clientName.startsWith("Moonlight Client") && s.clientUuid.isNullOrBlank() && resolvedIp != "127.0.0.1") {
                                    "Moonlight ($resolvedIp)"
                                } else s.clientName
                                val video = nativeVideo[s.videoThreadId]
                                if (s.clientIp != resolvedIp || s.clientName != resolvedName ||
                                    (video != null && (s.sessionId != video.sessionId || s.bitrate != video.appliedBitrateKbps || s.codec != video.codec))) {
                                    changed = true
                                    s.copy(clientName = resolvedName, clientIp = resolvedIp,
                                        sessionId = video?.sessionId ?: s.sessionId,
                                        bitrate = video?.appliedBitrateKbps ?: s.bitrate,
                                        codec = video?.codec ?: s.codec)
                                } else s
                            }
                            if (changed) {
                                host.publishSessions(updated)
                            }
                        }
                    }

                    // 6. Keep physical display orientation updated if auto_rotate is enabled
                    host.updateDisplayDimensionsIfAutoRotate()

                    // 7. Emit fresh PerfResponse to StateFlow
                    host.publishPerf()
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {}
            }
        }, "Sunshine-Telemetry").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        ++telemetryEpoch
        try {
            telemetryThread?.interrupt()
        } catch (_: Throwable) {}
        telemetryThread = null
    }
}
