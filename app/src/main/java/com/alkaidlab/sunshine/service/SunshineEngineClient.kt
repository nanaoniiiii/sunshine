package com.alkaidlab.sunshine.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.view.MotionEvent
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The host stays alive while Android creates a fresh :engine process after death. */
class SunshineEngineClient(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var enabled = false
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    @Volatile private var engine: ISunshineEngine? = null
    private var connection: ServiceConnection? = null
    private var recovery: Runnable? = null
    private var serverName = ""
    private var certPath = ""
    private var keyPath = ""
    private var statePath = ""
    // Reused: videoSessions() is polled via /api/diagnostics; Gson construction is not cheap.
    private val gson = com.google.gson.Gson()

    fun start(name: String, cert: String, key: String, state: String) {
        mainHandler.post {
            if (closed || enabled) return@post
            serverName = name
            certPath = cert
            keyPath = key
            statePath = state
            enabled = true
            bindEngine()
        }
    }

    private fun bindEngine() {
        if (!enabled || connection != null) return
        val epoch = ++generation
        val runtime = SunshineRuntimeManager.getInstance()
        val host = object : ISunshineEngineHost.Stub() {
            private fun current() = enabled && generation == epoch
            override fun streamStarted(threadId: Long, width: Int, height: Int, fps: Int,
                                       packetDuration: Int, surface: Surface, audio: Boolean, appId: Int, clientId: String, clientAddress: String): IntArray {
                synchronized(runtime) {
                    if (!current()) { surface.release(); return intArrayOf(width, height) }
                    runtime.onStreamStarted(width, height, fps, packetDuration, surface, audio, threadId, appId, clientId, clientAddress)
                }
                mainHandler.post { if (current()) cancelRecovery() }
                return runtime.getCaptureDimensions(width, height)
            }
            override fun streamStopped(threadId: Long, revision: Long) {
                synchronized(runtime) {
                    if (!current()) return
                    runtime.onStreamStopped(threadId)
                }
                // A normal disconnect is not an engine fault. Killing the idle
                // process also revokes MediaProjection on some Samsung builds.
                // Preserve the healthy engine and consent for the next client;
                // explicit recovery and Binder death still handle actual faults.
            }
            override fun appCatalog(): String = if (current()) runtime.nativeAppsJson() else "{\"apps\":[]}"
            override fun prepareApp(appId: Int): Boolean = current() && runtime.prepareApp(appId)
            override fun pinRequested() {
                synchronized(runtime) { if (current()) runtime.onPinRequested() }
            }
            override fun clientDiscovered(client: String) {
                synchronized(runtime) { if (current()) runtime.onClientDiscovered(client) }
            }
            override fun serverUuid(uuid: String) {
                synchronized(runtime) { if (current()) runtime.setServerUuid(uuid) }
            }
            override fun encoderError(error: String) {
                if (current()) {
                    runtime.reportEncoderError(error)
                    mainHandler.post { if (current() && !runtime.isStreaming.value) scheduleRecovery() }
                }
            }
            override fun remoteInput() { if (current()) runtime.onRemoteInput() }
            override fun motionEvent(event: MotionEvent) {
                try { if (current()) runtime.injectMotionEvent(event) } finally { event.recycle() }
            }
            override fun keyboardEvent(keyCode: Int, down: Boolean, modifiers: Int) {
                if (current() && !com.alkaidlab.sunshine.controller.ControllerAdapter.remoteKey(keyCode, down, modifiers)) runtime.handleKeyboardEvent(keyCode, down, modifiers)
            }
            override fun controllerState(id: Int, buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float) {
                if (current()) com.alkaidlab.sunshine.controller.ControllerAdapter.remoteState(id, buttons, lx, ly, rx, ry, lt, rt)
            }
            override fun controllerRemoved(id: Int) {
                if (current()) com.alkaidlab.sunshine.controller.ControllerAdapter.remoteRemoved(id)
            }
            override fun streamPaused(threadId: Long): Boolean = current() && runtime.pauseStreamSurface(threadId)
            override fun streamResized(threadId: Long, width: Int, height: Int, surface: Surface): Boolean =
                current() && runtime.resizeStreamSurface(threadId, width, height, surface)
            override fun readAudio(count: Int, mode: Int): FloatArray =
                if (current()) runtime.readAudio(count, mode) else floatArrayOf()
            override fun videoFrameProgress(threadId: Long, presentationTimeUs: Long, released: Boolean) {
                if (current()) runtime.onVideoFrameProgress(threadId, presentationTimeUs, released)
            }
        }
        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (!enabled || generation != epoch) return
                val remote = ISunshineEngine.Stub.asInterface(service)
                engine = remote
                scope.launch {
                    try {
                        if (!enabled || generation != epoch) return@launch
                        remote.setCaptureAuthorized(runtime.getMediaProjection() != null)
                        remote.configureEncoders(runtime.encoderH264, runtime.encoderHevc,
                            EncoderCapabilities.encoders.filter { it.mime == "video/avc" }.map { it.name }.distinct().toTypedArray(),
                            EncoderCapabilities.encoders.filter { it.mime == "video/hevc" }.map { it.name }.distinct().toTypedArray())
                        remote.configureVideo(runtime.configuredMinBitrate, runtime.configuredMaxBitrate, runtime.streamingSettings.value.maxFps)
                        val capture = runtime.getCaptureDimensions(1, 1)
                        val rotation = canvasRotation(runtime, runtime.autoRotateEnabled, runtime.rotationCompatEnabled)
                        remote.configureCanvas(capture[0], capture[1], rotation.follow,
                            rotation.compatibility, runtime.sourceDisplayId, sourceFrameLimit(runtime), runtime.streamDisplayMode.value == StreamDisplayMode.DEFAULT)
                        remote.start(host, serverName, certPath, keyPath, statePath, runtime.hevcEnabled)
                        synchronized(runtime) {
                            if (enabled && generation == epoch) runtime.setEngineReady(true)
                        }
                        SunshineLogManager.log("ENGINE", "Native engine connected (generation $epoch)")
                    } catch (e: RemoteException) {
                        mainHandler.post { disconnected(epoch) }
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) { disconnected(epoch) }
            override fun onBindingDied(name: ComponentName) { disconnected(epoch) }
            override fun onNullBinding(name: ComponentName) { disconnected(epoch) }
        }
        connection = newConnection
        if (!context.bindService(Intent(context, SunshineEngineService::class.java), newConnection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
            disconnected(epoch)
        }
    }

    private fun disconnected(epoch: Long) {
        if (generation != epoch) return
        val runtime = SunshineRuntimeManager.getInstance()
        com.alkaidlab.sunshine.controller.ControllerAdapter.releaseRemoteControllers()
        synchronized(runtime) {
            runtime.setEngineReady(false)
            ++generation // Reject late callbacks from the dead process.
            runtime.onEngineDisconnected()
        }
        engine = null
        cancelRecovery()
        unbind()
        if (enabled) mainHandler.postDelayed({ bindEngine() }, 300)
    }

    private fun unbind() {
        connection?.let {
            try { context.unbindService(it) } catch (_: IllegalArgumentException) {}
        }
        connection = null
    }

    fun scheduleRecovery(revision: Long = EngineSessions.MANUAL_RESTART) {
        cancelRecovery()
        val epoch = generation
        recovery = Runnable {
            recovery = null
            if (!enabled || generation != epoch || SunshineRuntimeManager.getInstance().isStreaming.value) return@Runnable
            val remote = engine ?: return@Runnable
            scope.launch {
                try {
                    if (remote.restartIfIdle(revision)) {
                        SunshineLogManager.log("RECOVERY", "Restarting idle native engine; host and capture stay alive")
                    }
                } catch (_: RemoteException) {
                    // Binding death handles reinitialization after the old process releases its ports.
                }
            }
        }.also { mainHandler.postDelayed(it, 800) }
    }

    private fun cancelRecovery() {
        recovery?.let { mainHandler.removeCallbacks(it) }
        recovery = null
    }

    fun submitPin(pin: String): Boolean {
        val remote = engine ?: return false
        return try { remote.submitPin(pin); true } catch (_: RemoteException) { false }
    }

    fun setCaptureAuthorized(authorized: Boolean) {
        // Do not block the host main thread while the engine marshals its FGS
        // transition to its own main thread.
        scope.launch { try { engine?.setCaptureAuthorized(authorized) } catch (e: RemoteException) {
            SunshineLogManager.log("ENGINE_WARN", "Capture foreground sync failed: ${e.message}")
        } }
    }

    fun startAudio(samplesPerPacket: Int) {
        val remote = engine ?: return
        scope.launch {
            try { remote.startAudio(samplesPerPacket) } catch (_: RemoteException) {}
        }
    }

    fun configureEncoders(avc: String, hevc: String) {
        try { engine?.configureEncoders(avc, hevc,
            EncoderCapabilities.encoders.filter { it.mime == "video/avc" }.map { it.name }.distinct().toTypedArray(),
            EncoderCapabilities.encoders.filter { it.mime == "video/hevc" }.map { it.name }.distinct().toTypedArray()) } catch (_: RemoteException) {}
    }

    fun configureVideo(minimum: Int, maximum: Int, maxFps: Int) {
        try { engine?.configureVideo(minimum, maximum, maxFps) } catch (_: RemoteException) {}
    }
    fun configureCanvas(width: Int, height: Int, follow: Boolean, compat: Boolean) {
        val runtime = SunshineRuntimeManager.getInstance()
        val id = runtime.sourceDisplayId
        val rotation = canvasRotation(runtime, follow, compat)
        try { engine?.configureCanvas(width, height, rotation.follow, rotation.compatibility, id, sourceFrameLimit(runtime),
            runtime.streamDisplayMode.value == StreamDisplayMode.DEFAULT) } catch (_: RemoteException) {}
    }
    private fun canvasRotation(runtime: SunshineRuntimeManager, follow: Boolean, compatibility: Boolean): CaptureSourcePolicy.Rotation {
        // The explicit stream mode owns the canvas: default follows actual host
        // pixels; fullscreen keeps the requested dimensions without swapping.
        return CaptureSourcePolicy.Rotation(false, false)
    }
    private fun sourceFrameLimit(runtime: SunshineRuntimeManager): Int {
        val state = runtime.virtualScreenState.value
        if (!state.active || state.mode != "desktop") return 0
        return runtime.virtualSourceFrameLimit.coerceIn(1, 240)
    }

    fun videoSessions(): List<com.alkaidlab.sunshine.data.NativeVideoSession> = try {
        val json = engine?.videoSnapshot() ?: "[]"
        gson.fromJson(json, Array<com.alkaidlab.sunshine.data.NativeVideoSession>::class.java).toList()
    } catch (_: Exception) { emptyList() }

    fun controlVideo(sessionId: Long, operation: Int, value: Int = 0): Boolean = try {
        engine?.controlVideo(sessionId, operation, value) == true
    } catch (_: RemoteException) { false }

    fun stop() {
        SunshineRuntimeManager.getInstance().setEngineReady(false)
        closed = true
        enabled = false
        ++generation
        cancelRecovery()
        mainHandler.removeCallbacksAndMessages(null)
        try { engine?.shutdown() } catch (_: RemoteException) {}
        engine = null
        unbind()
        scope.cancel()
    }
}
