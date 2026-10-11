package com.alkaidlab.sunshine.service

import android.content.Context
import android.content.Intent
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.alkaidlab.sunshine.data.PerfResponse
import com.alkaidlab.sunshine.data.RuntimeSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

class SunshineRuntimeManager private constructor() {

    companion object {
        @Volatile
        private var instance: SunshineRuntimeManager? = null

        @JvmStatic
        fun getInstance(): SunshineRuntimeManager {
            return instance ?: synchronized(this) {
                instance ?: SunshineRuntimeManager().also { instance = it }
            }
        }
    }

    private var context: Context? = null
    @Volatile private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile var engineClient: SunshineEngineClient? = null
    private val frameDispatcher = SunshineFrameDispatcher()

    // Streaming State
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _pinRequested = MutableStateFlow<String?>(null)
    val pinRequested: StateFlow<String?> = _pinRequested.asStateFlow()

    private val _activeSessionsFlow = MutableStateFlow<List<RuntimeSession>>(emptyList())
    val activeSessionsFlow: StateFlow<List<RuntimeSession>> = _activeSessionsFlow.asStateFlow()

    private val _perfFlow = MutableStateFlow(PerfResponse())
    val perfFlow: StateFlow<PerfResponse> = _perfFlow.asStateFlow()

    private val activeSessions = CopyOnWriteArrayList<RuntimeSession>()
    private var currentStreamSurface: Surface? = null
    private var streamStartTime: Long = 0

    @Volatile
    private var lastDiscoveredClient: String? = null

    // Telemetry & Config State
    @Volatile private var lastFps: Double = 60.0
    @Volatile private var lastEncodeTimeMs: Double = 1.8
    @Volatile private var lastNetworkLatencyMs: Double = 1.2
    @Volatile private var lastBitrate: Int = 10000
    private var lastWidth: Int = 0
    private var lastHeight: Int = 0
    @Volatile private var lastCodec: String = "HEVC (H.265)"
    private var clientUuid: String = ""

    @Volatile var hevcEnabled: Boolean = true
        private set
    @Volatile var autoRotateEnabled: Boolean = true
        private set
    @Volatile var autoScaleEnabled: Boolean = true
        private set
    @Volatile var configuredMaxBitrate: Int = 50000
        private set
    @Volatile var configuredMinBitrate: Int = 5000
        private set

    private var telemetryThread: Thread? = null
    private var prevTxBytes: Long = -1L
    private var prevTxTimeMs: Long = 0L

    // Shizuku Input injection reflection
    private var inputManager: Any? = null
    private var injectInputMethod: Method? = null

    fun initialize(appContext: Context) {
        this.context = appContext.applicationContext
        com.connect_screen.mirror.job.SunshineMouse.setContext(appContext)
        reloadConfig(this.context)
        initShizukuInput()
        frameDispatcher.setOnSurfaceEvictedListener { _ ->
            // Rendering failure is not proof of native disconnect. Preserve lifecycle identities
            // until the engine reports stop, so a bad surface cannot trigger a global restart.
            SunshineLogManager.log("STREAM", "Client surface evicted; awaiting native disconnect")
        }
    }

    fun reloadConfig(ctx: Context? = this.context) {
        val c = ctx ?: return
        try {
            val confFile = java.io.File(c.filesDir, "sunshine.conf")
            if (confFile.exists()) {
                val props = mutableMapOf<String, String>()
                confFile.readLines().forEach { line ->
                    val t = line.trim()
                    if (t.isNotEmpty() && !t.startsWith("#") && t.contains("=")) {
                        val parts = t.split("=", limit = 2)
                        props[parts[0].trim()] = parts[1].trim()
                    }
                }
                hevcEnabled = (props["hevc_mode"] ?: "enabled") != "disabled"
                autoRotateEnabled = (props["auto_rotate"] ?: "true") != "false"
                autoScaleEnabled = (props["auto_scale"] ?: "true") != "false"
                configuredMinBitrate = props["min_bitrate"]?.toIntOrNull() ?: 5000
                configuredMaxBitrate = props["max_bitrate"]?.toIntOrNull() ?: 50000
                lastCodec = if (hevcEnabled) "HEVC (H.265)" else "AVC (H.264)"
            }
        } catch (e: Exception) {
            SunshineLogManager.log("CONFIG_WARN", "reloadConfig error: ${e.message}")
        }
    }

    fun launchAppByCommand(cmd: String): Boolean {
        val ctx = context ?: return false
        val pkg = cmd.trim()
        if (pkg.isEmpty()) return false
        return try {
            val pm = ctx.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launchIntent)
                SunshineLogManager.log("APP_LAUNCH", "Launched package: $pkg")
                true
            } else if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                method.isAccessible = true
                method.invoke(null, arrayOf("sh", "-c", "monkey -p $pkg -c android.intent.category.LAUNCHER 1 || $pkg"), null, null)
                SunshineLogManager.log("APP_LAUNCH", "Executed command via Shizuku: $pkg")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            SunshineLogManager.log("APP_ERR", "Failed to launch $pkg: ${e.message}")
            false
        }
    }

    private fun startTelemetryLoop() {
        if (telemetryThread?.isAlive == true) return
        prevTxBytes = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
        prevTxTimeMs = SystemClock.elapsedRealtime()

        telemetryThread = Thread({
            val timeRegex = Regex("time=([0-9.]+)")
            while (_isStreaming.value && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(1000)
                    if (!_isStreaming.value) break

                    // 1. Sample real-time FPS & GPU->Hardware Encoder surface latency
                    val (measuredFps, measuredEncodeMs) = frameDispatcher.sampleTelemetry()
                    lastFps = if (measuredFps > 0.0) measuredFps else frameDispatcher.targetFps.toDouble()
                    lastEncodeTimeMs = measuredEncodeMs

                    // 2. Sample real-time process network TX bitrate via TrafficStats
                    val nowTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
                    val nowMs = SystemClock.elapsedRealtime()
                    if (prevTxBytes > 0 && nowTx >= prevTxBytes && nowMs > prevTxTimeMs) {
                        val deltaBytes = nowTx - prevTxBytes
                        val elapsedSec = (nowMs - prevTxTimeMs) / 1000.0
                        val kbps = ((deltaBytes * 8.0) / elapsedSec / 1000.0).toInt()
                        if (kbps > 50) {
                            lastBitrate = kbps
                        }
                    }
                    prevTxBytes = nowTx
                    prevTxTimeMs = nowMs

                    // 3. Auto-detect connected peer IPs from /proc/net/tcp & /proc/net/tcp6
                    val peerIps = detectConnectedPeerIps()
                    if (peerIps.isNotEmpty()) {
                        lastDiscoveredClient = peerIps.last()
                    }

                    // 4. Measure real network RTT to active peer IP or LAN gateway
                    val probeIp = activeSessions.lastOrNull()?.clientIp?.takeIf { it != "127.0.0.1" }
                        ?: peerIps.lastOrNull()
                        ?: deriveGatewayIp(SunshineService.currentIp)
                    if (probeIp != null && probeIp != "127.0.0.1") {
                        val rtt = measurePingRttMs(probeIp, timeRegex)
                        if (rtt != null && rtt > 0.0) {
                            lastNetworkLatencyMs = Math.round((lastNetworkLatencyMs * 0.3 + rtt * 0.7) * 100.0) / 100.0
                        }
                    }

                    // 5. Sync session metadata (peer IP, live per-client bitrate, codec)
                    synchronized(this@SunshineRuntimeManager) {
                        if (activeSessions.isNotEmpty()) {
                            val perClientBitrate = (lastBitrate / activeSessions.size.coerceAtLeast(1)).coerceAtLeast(500)
                            var changed = false
                            val updated = activeSessions.mapIndexed { idx, s ->
                                val resolvedIp = if (s.clientIp == "127.0.0.1" && peerIps.isNotEmpty()) {
                                    peerIps.getOrElse(idx) { peerIps.last() }
                                } else s.clientIp
                                val resolvedName = if (s.clientName.startsWith("Moonlight Client") && resolvedIp != "127.0.0.1") {
                                    "Moonlight ($resolvedIp)"
                                } else s.clientName
                                if (s.clientIp != resolvedIp || s.clientName != resolvedName || s.bitrate != perClientBitrate || s.codec != lastCodec) {
                                    changed = true
                                    s.copy(clientName = resolvedName, clientIp = resolvedIp, bitrate = perClientBitrate, codec = lastCodec)
                                } else s
                            }
                            if (changed) {
                                activeSessions.clear()
                                activeSessions.addAll(updated)
                                _activeSessionsFlow.value = activeSessions.toList()
                            }
                        }
                    }

                    // 6. Keep physical display orientation updated if auto_rotate is enabled
                    if (autoRotateEnabled) {
                        com.connect_screen.mirror.job.SunshineMouse.updateDisplayDimensions()
                    }

                    // 7. Emit fresh PerfResponse to StateFlow
                    _perfFlow.value = getPerfSnapshot()
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {}
            }
        }, "Sunshine-Telemetry").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopTelemetryLoop() {
        try {
            telemetryThread?.interrupt()
        } catch (_: Throwable) {}
        telemetryThread = null
    }

    private fun detectConnectedPeerIps(): List<String> {
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

    private fun decodeProcHexIp(hex: String): String? {
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

    private fun deriveGatewayIp(lanIp: String): String? {
        val parts = lanIp.split(".")
        return if (parts.size == 4 && parts[0] != "127") {
            "${parts[0]}.${parts[1]}.${parts[2]}.1"
        } else null
    }

    private fun measurePingRttMs(ip: String, timeRegex: Regex): Double? {
        return try {
            val proc = ProcessBuilder("ping", "-c", "1", "-W", "1", ip)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroy()
                return null
            }
            val output = proc.inputStream.bufferedReader().readText()
            val match = timeRegex.find(output)
            match?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    fun setMediaProjection(projection: MediaProjection?) {
        this.mediaProjection = projection
        SunshineLogManager.log("MEDIA_PROJ", if (projection != null) "MediaProjection set" else "MediaProjection cleared")
    }

    fun getMediaProjection(): MediaProjection? = mediaProjection

    fun getCaptureDimensions(width: Int, height: Int): IntArray =
        if (frameDispatcher.isRunning) intArrayOf(frameDispatcher.sourceWidth, frameDispatcher.sourceHeight)
        else intArrayOf(width, height)

    fun submitPin(pin: String): Boolean {
        val cleanPin = pin.trim()
        if (cleanPin.length != 4 || !cleanPin.all { it.isDigit() }) {
            SunshineLogManager.log("PIN_ERR", "Invalid PIN format: $cleanPin")
            return false
        }
        return try {
            if (engineClient?.submitPin(cleanPin) != true) return false
            _pinRequested.value = null
            SunshineLogManager.log("PIN", "Native submitPin completed for $cleanPin")
            true
        } catch (e: Throwable) {
            SunshineLogManager.log("PIN_ERR", "Failed native submitPin: ${e.message}")
            false
        }
    }

    fun onPinRequested() {
        SunshineLogManager.log("PIN", "Moonlight client requested PIN pairing")
        _pinRequested.value = "PIN_REQUIRED"
    }

    fun dismissPinRequest() {
        _pinRequested.value = null
    }

    @Synchronized
    fun onStreamStarted(width: Int, height: Int, fps: Int, packetDuration: Int, surface: Surface,
                        shouldSendAudio: Boolean, callerThreadId: Long) {
        val sessionIndex = activeSessions.size + 1
        reloadConfig()
        val peerIps = detectConnectedPeerIps()
        if (peerIps.isNotEmpty()) {
            lastDiscoveredClient = peerIps.last()
        }
        val cIp = peerIps.lastOrNull() ?: lastDiscoveredClient ?: "127.0.0.1"
        val cName = if (cIp != "127.0.0.1") "Moonlight ($cIp)" else "Moonlight Client #$sessionIndex"
        SunshineLogManager.log("STREAM", "Stream started ($cName, tid=$callerThreadId): ${width}x$height @ ${fps}fps, packetDuration=${packetDuration}ms, audio=$shouldSendAudio")

        lastWidth = width
        lastHeight = height
        lastFps = fps.toDouble()
        lastBitrate = (configuredMaxBitrate / 2).coerceIn(configuredMinBitrate, configuredMaxBitrate)
        lastCodec = if (hevcEnabled) "HEVC (H.265)" else "AVC (H.264)"
        if (streamStartTime == 0L) {
            streamStartTime = System.currentTimeMillis()
        }

        com.connect_screen.mirror.job.SunshineMouse.setActiveClientDimensions(width, height)

        val session = RuntimeSession(
            clientName = cName,
            clientIp = cIp,
            width = width,
            height = height,
            fps = fps,
            bitrate = lastBitrate,
            codec = lastCodec,
            startTime = System.currentTimeMillis(),
            surface = surface,
            videoThreadId = callerThreadId
        )
        activeSessions.add(session)
        _activeSessionsFlow.value = activeSessions.toList()
        _isStreaming.value = true
        _perfFlow.value = getPerfSnapshot()
        startTelemetryLoop()

        // Create VirtualDisplay feeding frameDispatcher on main thread
        Handler(Looper.getMainLooper()).post {
            if (activeSessions.none { it.videoThreadId == callerThreadId && it.surface === surface }) return@post
            val mp = mediaProjection
            if (mp != null) {
                if (!frameDispatcher.isRunning || virtualDisplay == null) {
                    try {
                        val inputSurface = frameDispatcher.start(width, height)
                        val densityDpi = context?.resources?.displayMetrics?.densityDpi ?: 300
                        virtualDisplay?.release()
                        virtualDisplay = mp.createVirtualDisplay(
                            "SunshineVirtualDisplay",
                            width,
                            height,
                            densityDpi,
                            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            inputSurface,
                            null,
                            null
                        )
                        SunshineLogManager.log("VD", "VirtualDisplay created feeding SunshineFrameDispatcher (${width}x$height @ ${densityDpi}dpi)")
                    } catch (e: Exception) {
                        SunshineLogManager.log("VD_ERR", "Error initializing VirtualDisplay/Dispatcher: ${e.message}")
                    }
                }

                // Register client surface into frame dispatcher with target FPS
                frameDispatcher.addSurface(surface, width, height, fps)
                SunshineLogManager.log("VD", "Client ($cName) surface registered into frame dispatcher")
            } else {
                SunshineLogManager.log("VD_WARN", "MediaProjection is null, cannot create VirtualDisplay yet")
            }

            // Start Audio capture if requested
            if (shouldSendAudio) {
                startAudioCapture(packetDuration)
            } else {
                SunshineLogManager.log("AUDIO", "Client requested no audio")
            }
        }
    }

    @Synchronized
    fun onStreamStopped(callerThreadId: Long) {
        val deadSession = activeSessions.find { it.videoThreadId == callerThreadId } ?: return
        activeSessions.remove(deadSession)
        deadSession.surface?.let { surface ->
            // The synchronous Binder callback keeps native AMediaCodec_stop behind EGL teardown.
            frameDispatcher.removeSurfaceSync(surface)
            surface.release()
        }
        SunshineLogManager.log("STREAM", "Disconnected tid=$callerThreadId; remaining=${activeSessions.size}")
        val remaining = activeSessions.lastOrNull()
        if (remaining != null) {
            com.connect_screen.mirror.job.SunshineMouse.setActiveClientDimensions(remaining.width, remaining.height)
        } else {
            _isStreaming.value = false
            stopTelemetryLoop()
            streamStartTime = 0L
            // Keep MediaProjection, its one VirtualDisplay, and AudioRecord alive for reconnects.
        }
        _activeSessionsFlow.value = activeSessions.toList()
        _perfFlow.value = getPerfSnapshot()
    }

    @Synchronized
    fun onEngineDisconnected() {
        activeSessions.toList().forEach { onStreamStopped(it.videoThreadId) }
        _pinRequested.value = null
    }

    fun onProjectionStopped(projection: MediaProjection) {
        Handler(Looper.getMainLooper()).post {
            if (mediaProjection !== projection) return@post
            releaseCapture()
            SunshineLogManager.log("MEDIA_PROJ", "Capture revoked; waiting for fresh user consent")
        }
    }

    fun triggerAutoRecovery() {
        Handler(Looper.getMainLooper()).post { engineClient?.scheduleRecovery() }
    }

    @Synchronized
    fun onClientDiscovered(client: String) {
        SunshineLogManager.log("DISCOVERY", "Discovered client: $client")
        lastDiscoveredClient = client
        if (activeSessions.isNotEmpty()) {
            val updated = activeSessions.map { s ->
                if (s.clientName.startsWith("Moonlight Client") || s.clientIp == "127.0.0.1") {
                    s.copy(clientName = client, clientIp = client)
                } else s
            }
            activeSessions.clear()
            activeSessions.addAll(updated)
            _activeSessionsFlow.value = activeSessions.toList()
        }
    }

    fun setServerUuid(uuid: String) {
        this.clientUuid = uuid
        SunshineLogManager.log("SERVER", "Server UUID: $uuid")
    }

    fun isClientActive(uuid: String): Boolean {
        return _isStreaming.value && clientUuid == uuid
    }

    fun getActiveSessions(): List<RuntimeSession> = activeSessions.toList()

    fun getPerfSnapshot(): PerfResponse {
        return if (_isStreaming.value) {
            PerfResponse(
                active = true,
                fps = lastFps,
                encodeTimeMs = lastEncodeTimeMs,
                networkLatencyMs = lastNetworkLatencyMs,
                bitrateKbps = lastBitrate
            )
        } else {
            PerfResponse(active = false)
        }
    }

    fun restart(): Boolean {
        if (_isStreaming.value || engineClient == null) return false
        SunshineLogManager.log("RESTART", "Requesting idle engine restart")
        triggerAutoRecovery()
        return true
    }

    private var isAudioCaptureInitialized = false

    // Audio capture via AudioPlaybackCapture
    private fun startAudioCapture(packetDuration: Int = 10) {
        val ctx = context ?: return
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            SunshineLogManager.log("AUDIO_WARN", "Audio permission not granted; streaming video only")
            return
        }
        val duration = if (packetDuration > 0) packetDuration else 10
        val samplesPerPacket = (duration * 48000) / 1000
        if (isAudioCaptureInitialized && audioRecord != null) {
            engineClient?.startAudio(samplesPerPacket)
            return
        }
        val mp = mediaProjection ?: run {
            SunshineLogManager.log("AUDIO_WARN", "MediaProjection is null, cannot capture audio")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                    .excludeUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()

                val sampleRate = 48000
                val minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )

                val bufferSize = if (minBuffer > 0) minBuffer * 2 else 48000 * 4
                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                record.startRecording()
                audioRecord = record
                isAudioCaptureInitialized = true
                engineClient?.startAudio(samplesPerPacket)
                SunshineLogManager.log("AUDIO", "Audio playback capture started: 48000Hz stereo float ($samplesPerPacket samples/pkt)")
            } catch (e: Throwable) {
                SunshineLogManager.log("AUDIO_ERR", "Failed to start AudioPlaybackCapture: ${e.message}")
            }
        }
    }

    fun readAudio(count: Int, mode: Int): FloatArray {
        // Bound the Binder payload; normal 10ms stereo packets contain 960 floats.
        val buffer = FloatArray(count.coerceIn(0, 48000))
        val record = audioRecord ?: return floatArrayOf()
        val read = try { record.read(buffer, 0, buffer.size, mode) } catch (_: Exception) { 0 }
        return if (read > 0) buffer.copyOf(read) else floatArrayOf()
    }

    private fun releaseCapture() {
        frameDispatcher.stop()
        virtualDisplay?.release()
        virtualDisplay = null
        val record = audioRecord
        audioRecord = null
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        isAudioCaptureInitialized = false
        val projection = mediaProjection
        mediaProjection = null
        try { projection?.stop() } catch (_: Exception) {}
    }

    @Synchronized
    fun releaseAll() {
        onEngineDisconnected()
        releaseCapture()
        currentStreamSurface = null
        com.connect_screen.mirror.job.SunshineMouse.reset()
    }

    fun getLastWidth(): Int = if (lastWidth > 0) lastWidth else 720
    fun getLastHeight(): Int = if (lastHeight > 0) lastHeight else 1600

    // Shizuku Input Injection
    private var isBoundViaShizuku = false

    fun initShizukuInput() {
        try {
            val hasShizuku = try {
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }

            if (hasShizuku) {
                val rawBinder = rikka.shizuku.SystemServiceHelper.getSystemService("input")
                val wrappedBinder = rikka.shizuku.ShizukuBinderWrapper(rawBinder)
                val iInputManagerStub = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterfaceMethod = iInputManagerStub.getMethod("asInterface", android.os.IBinder::class.java)
                inputManager = asInterfaceMethod.invoke(null, wrappedBinder)

                injectInputMethod = inputManager?.javaClass?.methods?.find {
                    it.name == "injectInputEvent" && it.parameterTypes.size == 2
                }
                isBoundViaShizuku = true
                SunshineLogManager.log("SHIZUKU", "InputManager bound via Shizuku successfully (wrapped binder)")
                return
            }

            if (inputManager == null) {
                val serviceManagerClass = Class.forName("android.os.ServiceManager")
                val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
                val binder = getServiceMethod.invoke(null, "input") as? android.os.IBinder
                if (binder != null) {
                    val iInputManagerStub = Class.forName("android.hardware.input.IInputManager\$Stub")
                    val asInterfaceMethod = iInputManagerStub.getMethod("asInterface", android.os.IBinder::class.java)
                    inputManager = asInterfaceMethod.invoke(null, binder)

                    injectInputMethod = inputManager?.javaClass?.methods?.find {
                        it.name == "injectInputEvent" && it.parameterTypes.size == 2
                    }
                    SunshineLogManager.log("INPUT", "InputManager bound via standard ServiceManager")
                }
            }
        } catch (e: Exception) {
            SunshineLogManager.log("SHIZUKU_WARN", "Shizuku InputManager init: ${e.message}")
        }
    }

    fun injectMotionEvent(event: MotionEvent) {
        if (SunshineEngineBridge.injectMotion(event)) return
        if (!isBoundViaShizuku) {
            initShizukuInput()
        }
        val im = inputManager ?: return
        val method = injectInputMethod ?: return
        try {
            method.invoke(im, event, 0)
        } catch (e: Exception) {
            SunshineLogManager.log("INJECT_ERR", "injectInputEvent error: ${e.message}")
        }
    }

    fun handleTouchPacket(action: Int, pointerId: Int, flags: Int, x: Float, y: Float, pressure: Float, contactArea: Float) {
        com.connect_screen.mirror.job.SunshineMouse.handleTouchPacket(action, flags, pointerId, x, y, pressure, contactArea, 0f)
    }

    fun handleAbsMouseMove(x: Float, y: Float, width: Float, height: Float) {
        com.connect_screen.mirror.job.SunshineMouse.handleAbsMouseMovePacket(x, y, width, height)
    }

    fun handleMouseButton(button: Int, isDown: Boolean) {
        com.connect_screen.mirror.job.SunshineMouse.handleLeftMouseButton(!isDown)
    }

    fun handleKeyboardEvent(keyCode: Int, isDown: Boolean, modifiers: Int) {
        if (SunshineEngineBridge.injectKeyboard(keyCode, isDown, modifiers)) return
        if (inputManager == null) {
            initShizukuInput()
        }
        val im = inputManager ?: return
        val method = injectInputMethod ?: return
        try {
            val now = SystemClock.uptimeMillis()
            val action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
            val keyEvent = KeyEvent(now, now, action, keyCode, 0, modifiers, KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
            method.invoke(im, keyEvent, 0)
        } catch (_: Exception) {}
    }
}
