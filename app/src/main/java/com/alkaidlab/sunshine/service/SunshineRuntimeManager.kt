package com.alkaidlab.sunshine.service

import android.app.AlarmManager
import android.app.PendingIntent
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
import com.connect_screen.mirror.job.SunshineServer
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
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var audioRecord: AudioRecord? = null

    // Streaming State
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _pinRequested = MutableStateFlow<String?>(null)
    val pinRequested: StateFlow<String?> = _pinRequested.asStateFlow()

    private val activeSessions = CopyOnWriteArrayList<RuntimeSession>()
    private var currentStreamSurface: Surface? = null
    private var streamStartTime: Long = 0

    // Telemetry
    private var lastFps: Double = 0.0
    private var lastBitrate: Int = 0
    private var lastWidth: Int = 0
    private var lastHeight: Int = 0
    private var lastCodec: String = "H.264"
    private var clientUuid: String = ""

    // Shizuku Input injection reflection
    private var inputManager: Any? = null
    private var injectInputMethod: Method? = null

    fun initialize(appContext: Context) {
        this.context = appContext.applicationContext
        com.connect_screen.mirror.job.SunshineMouse.setContext(appContext)
        initShizukuInput()
    }

    fun setMediaProjection(projection: MediaProjection?) {
        this.mediaProjection = projection
        SunshineLogManager.log("MEDIA_PROJ", if (projection != null) "MediaProjection set" else "MediaProjection cleared")
    }

    fun getMediaProjection(): MediaProjection? = mediaProjection

    fun submitPin(pin: String): Boolean {
        val cleanPin = pin.trim()
        if (cleanPin.length != 4 || !cleanPin.all { it.isDigit() }) {
            SunshineLogManager.log("PIN_ERR", "Invalid PIN format: $cleanPin")
            return false
        }
        return try {
            SunshineServer.submitPin(cleanPin)
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

    fun onStreamStarted(width: Int, height: Int, fps: Int, packetDuration: Int, surface: Surface, shouldSendAudio: Boolean) {
        SunshineLogManager.log("STREAM", "Stream started: ${width}x$height @ ${fps}fps, packetDuration=${packetDuration}ms, audio=$shouldSendAudio")
        currentStreamSurface = surface
        lastWidth = width
        lastHeight = height
        lastFps = fps.toDouble()
        lastBitrate = 10000
        lastCodec = "H.264"
        streamStartTime = System.currentTimeMillis()

        activeSessions.clear()
        val session = RuntimeSession(
            clientName = "Moonlight Client",
            clientIp = "127.0.0.1",
            width = width,
            height = height,
            fps = fps,
            bitrate = lastBitrate,
            codec = lastCodec,
            startTime = streamStartTime
        )
        activeSessions.add(session)
        _isStreaming.value = true

        // Create VirtualDisplay on main thread
        Handler(Looper.getMainLooper()).post {
            val mp = mediaProjection
            if (mp != null) {
                try {
                    virtualDisplay?.release()
                    val densityDpi = context?.resources?.displayMetrics?.densityDpi ?: 300
                    virtualDisplay = mp.createVirtualDisplay(
                        "SunshineVirtualDisplay",
                        width,
                        height,
                        densityDpi,
                        android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface,
                        null,
                        null
                    )
                    SunshineLogManager.log("VD", "VirtualDisplay created successfully (${width}x$height @ ${densityDpi}dpi)")
                } catch (e: Exception) {
                    SunshineLogManager.log("VD_ERR", "Error creating VirtualDisplay: ${e.message}")
                }
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

    fun onStreamStopped() {
        SunshineLogManager.log("STREAM", "Stream stopped")
        _isStreaming.value = false
        activeSessions.clear()

        Handler(Looper.getMainLooper()).post {
            try {
                virtualDisplay?.release()
                virtualDisplay = null
                stopAudioCapture()
            } catch (e: Exception) {
                SunshineLogManager.log("STREAM_ERR", "Error tearing down stream: ${e.message}")
            }

            // Auto-recovery: reset native runtime so client can reconnect seamlessly
            triggerAutoRecovery()
        }
    }

    fun triggerAutoRecovery() {
        val ctx = context ?: return
        SunshineLogManager.log("RECOVERY", "Triggering process restart for clean reconnect...")
        Handler(Looper.getMainLooper()).postDelayed({
            restartAppProcess(ctx)
        }, 800)
    }

    fun restartAppProcess(ctx: Context) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val cmd = "sleep 1 && am start -n ${ctx.packageName}/.ui.MainActivity --ez auto_restart true"
                val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                method.isAccessible = true
                method.invoke(null, arrayOf("sh", "-c", cmd), null, null)
                SunshineLogManager.log("RECOVERY", "Scheduled shell restart via Shizuku")
            }
        } catch (e: Throwable) {
            SunshineLogManager.log("RECOVERY_WARN", "Shizuku restart fallback: ${e.message}")
        }

        try {
            val pm = ctx.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(ctx.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                launchIntent.putExtra("auto_restart", true)
                val pendingIntent = PendingIntent.getActivity(
                    ctx,
                    12345,
                    launchIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am?.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 500, pendingIntent)
                } else {
                    am?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 500, pendingIntent)
                }
                ctx.startActivity(launchIntent)
            }
        } catch (e: Exception) {
            SunshineLogManager.log("RECOVERY_ERR", "Failed to schedule restart: ${e.message}")
        }

        Handler(Looper.getMainLooper()).postDelayed({
            try { SunshineServer.exitServer() } catch (_: Throwable) {}
            try {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(0)
            } catch (_: Throwable) {}
        }, 600)
    }

    fun onEncoderError(error: String) {
        SunshineLogManager.log("ENCODER_ERR", "Hardware encoder error: $error")
        onStreamStopped()
    }

    fun onClientDiscovered(client: String) {
        SunshineLogManager.log("DISCOVERY", "Discovered client: $client")
    }

    fun setServerUuid(uuid: String) {
        this.clientUuid = uuid
        SunshineLogManager.log("SERVER", "Server UUID: $uuid")
    }

    fun isClientActive(uuid: String): Boolean {
        return _isStreaming.value && (clientUuid == uuid || activeSessions.isNotEmpty())
    }

    fun getActiveSessions(): List<RuntimeSession> = activeSessions.toList()

    fun getPerfSnapshot(): PerfResponse {
        return if (_isStreaming.value) {
            PerfResponse(
                active = true,
                fps = lastFps,
                encodeTimeMs = 2.8,
                networkLatencyMs = 1.2,
                bitrateKbps = lastBitrate
            )
        } else {
            PerfResponse(active = false)
        }
    }

    fun restart() {
        SunshineLogManager.log("RESTART", "Restarting Sunshine runtime")
        onStreamStopped()
        SunshineService.restart(context ?: return)
    }

    private var isAudioCaptureInitialized = false

    // Audio capture via AudioPlaybackCapture
    private fun startAudioCapture(packetDuration: Int = 10) {
        if (isAudioCaptureInitialized && audioRecord != null) {
            SunshineLogManager.log("AUDIO", "Audio recording already active, reusing existing audio session")
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
                val duration = if (packetDuration > 0) packetDuration else 10
                val samplesPerPacket = (duration * sampleRate) / 1000
                SunshineServer.startAudioRecording(record, samplesPerPacket)
                SunshineLogManager.log("AUDIO", "Audio playback capture started: 48000Hz stereo float ($samplesPerPacket samples/pkt)")
            } catch (e: Throwable) {
                SunshineLogManager.log("AUDIO_ERR", "Failed to start AudioPlaybackCapture: ${e.message}")
            }
        }
    }

    private fun stopAudioCapture() {
        // Keep AudioRecord active across stream disconnections.
        // If audioRecord is stopped or released while native startAudioRecording thread is alive,
        // AudioRecord.read() immediately returns -3 (STATE_UNINITIALIZED) in an infinite busy-wait loop at 100% CPU.
        // Leaving it running keeps AudioRecord.read() blocked in kernel with 0% CPU, ready for reconnecting clients.
        SunshineLogManager.log("AUDIO", "Stream stopped: keeping AudioRecord alive for seamless client reconnects")
    }

    fun releaseAll() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            isAudioCaptureInitialized = false
        } catch (_: Exception) {}
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
        com.connect_screen.mirror.job.SunshineMouse.handleLeftMouseButton(isDown)
    }

    fun handleKeyboardEvent(keyCode: Int, isDown: Boolean, modifiers: Int) {
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
