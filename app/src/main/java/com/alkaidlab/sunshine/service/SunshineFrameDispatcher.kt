package com.alkaidlab.sunshine.service

import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * High-performance hardware OpenGL ES 2.0 multi-surface frame dispatcher.
 * Captures screen frames once from Android VirtualDisplay via SurfaceTexture,
 * and distributes each frame to multiple AMediaCodec encoder surfaces
 * with GPU blitting and one latest texture; each encoder has its own frame deadline.
 */
class SunshineFrameDispatcher {

    data class ClientTarget(
        val surface: Surface,
        var eglSurface: EGLSurface? = null,
        val width: Int,
        val height: Int,
        val requestedFps: Int,
        val pacer: FramePacer,
        val threadId: Long = 0,
        var lastTextureVersion: Long = -1,
        var lastPresentationNs: Long = 0,
        var telemetryFrames: Int = 0,
        var telemetryHandoffNs: Long = 0,
        @Volatile var measuredHandoffMs: Double? = null,
        @Volatile var measuredFps: Double = 0.0,
        var failureCount: Int = 0
    )

    @Volatile private var stopping = false
    @Volatile private var glThread: HandlerThread? = null
    @Volatile private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay? = null
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext? = null
    private var dummySurface: EGLSurface? = null

    private var textureId: Int = 0
    private var programId: Int = 0
    private var aPositionHandle: Int = 0
    private var aTexCoordHandle: Int = 0
    private var uTextureMatrixHandle: Int = 0
    private var uSamplerHandle: Int = 0

    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private val clientTargets = CopyOnWriteArrayList<ClientTarget>()
    private val texMatrix = FloatArray(16)

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer
    private val clockwiseCoords = coordinates(floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f))
    private val counterclockwiseCoords = coordinates(floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
    private fun coordinates(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    @Volatile var sourceRotation: Int = 0
    @Volatile var sourceIsVirtual: Boolean = false

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var sourceWidth: Int = 1920
        private set

    @Volatile
    var sourceHeight: Int = 1080
        private set

    init {
        val vertexCoords = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )
        val texCoords = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )

        vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertexCoords)
                position(0)
            }

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(texCoords)
                position(0)
            }
    }

    private var hasDrawnAnyTexture = false
    private var textureVersion = 0L
    private var pendingTexture = false
    private var frameScheduled = false
    @Volatile private var settings = StreamingSettings()

    val targetFps: Int
        get() = clientTargets.maxOfOrNull { minOf(it.requestedFps, settings.maxFps) } ?: 0

    private val telemetryLock = Any()
    private var windowStartNs = System.nanoTime()
    private var windowCaptures = 0
    private var windowSubmissions = 0
    private var windowHandoffNs = 0L
    private var totalSubmitted = 0L
    private var totalRepeated = 0L
    private var totalCoalesced = 0L
    private var totalFailures = 0L
    private var slowHandoffs = 0L
    private var lastCaptureNs = 0L
    private val handoffSamples = LongArray(256)
    private var handoffSampleCount = 0
    private var handoffSampleIndex = 0
    @Volatile var latestMeasuredFps = 0.0
        private set
    @Volatile var latestEncodeTimeMs = 0.0
        private set
    @Volatile private var latestCaptureFps = 0.0

    fun configure(updated: StreamingSettings) {
        settings = updated
        glHandler?.post {
            clientTargets.forEach { it.pacer.setFps(minOf(it.requestedFps, updated.maxFps)) }
            rescheduleFrame()
        }
    }

    fun sampleTelemetry(): Pair<Double, Double> {
        synchronized(telemetryLock) {
            val nowNs = System.nanoTime()
            val elapsedSec = (nowNs - windowStartNs) / 1_000_000_000.0
            if (elapsedSec >= 0.25) {
                clientTargets.forEach {
                    it.measuredFps = it.telemetryFrames / elapsedSec
                    it.measuredHandoffMs = if (it.telemetryFrames > 0) it.telemetryHandoffNs / it.telemetryFrames / 1_000_000.0 else null
                    it.telemetryFrames = 0; it.telemetryHandoffNs = 0
                }
                latestMeasuredFps = clientTargets.maxOfOrNull { it.measuredFps } ?: 0.0
                latestCaptureFps = windowCaptures / elapsedSec
                latestEncodeTimeMs = if (windowSubmissions > 0) windowHandoffNs / windowSubmissions / 1_000_000.0 else 0.0
                windowStartNs = nowNs
                windowCaptures = 0
                windowSubmissions = 0
                windowHandoffNs = 0L
            }
            return latestMeasuredFps to latestEncodeTimeMs
        }
    }

    fun diagnostics(): com.alkaidlab.sunshine.data.FrameDiagnostics = synchronized(telemetryLock) {
        val sorted = handoffSamples.take(handoffSampleCount).sorted()
        val p95 = sorted.getOrNull(((sorted.size * 95 + 99) / 100 - 1).coerceAtLeast(0)) ?: 0L
        com.alkaidlab.sunshine.data.FrameDiagnostics(
            captureWidth = sourceWidth,
            captureHeight = sourceHeight,
            captureFps = latestCaptureFps,
            submittedFrames = totalSubmitted,
            repeatedFrames = totalRepeated,
            coalescedFrames = totalCoalesced,
            surfaceFailures = totalFailures,
            slowHandoffs = slowHandoffs,
            handoffP95Ms = p95 / 1_000_000.0,
            handoffMaxMs = (sorted.lastOrNull() ?: 0L) / 1_000_000.0,
            captureAgeMs = if (lastCaptureNs == 0L) null else (System.nanoTime() - lastCaptureNs) / 1_000_000.0,
            targets = clientTargets.map { com.alkaidlab.sunshine.data.FrameTargetDiagnostics(
                it.width, it.height, it.requestedFps, minOf(it.requestedFps, settings.maxFps), it.measuredFps,
                it.threadId, it.measuredHandoffMs,
                CaptureGeometry.orientedFit(sourceWidth, sourceHeight, it.width, it.height,
                    CaptureGeometry.compatibilityTurn(sourceWidth, sourceHeight, it.width, it.height, sourceRotation, settings.rotationCompat && !sourceIsVirtual)).let { vp ->
                    listOf(vp.x, vp.y, vp.width, vp.height)
                }) }
        )
    }

    private val latchFrameRunnable = Runnable {
        if (!isRunning || !pendingTexture) return@Runnable
        pendingTexture = false
        val st = surfaceTexture ?: return@Runnable
        try {
            restoreDummySurface()
            st.updateTexImage() // SurfaceTexture acquires the newest queued buffer.
            st.getTransformMatrix(texMatrix)
            synchronized(telemetryLock) {
                if (hasDrawnAnyTexture && clientTargets.isNotEmpty() && clientTargets.none { it.lastTextureVersion == textureVersion }) totalCoalesced++
                windowCaptures++
                lastCaptureNs = System.nanoTime()
            }
            textureVersion++
            hasDrawnAnyTexture = true
        } catch (e: RuntimeException) {
            SunshineLogManager.log("DISPATCHER_WARN", "Cannot acquire capture frame: ${e.message}")
            return@Runnable
        }
        rescheduleFrame()
    }

    private val framePumpRunnable = Runnable {
        frameScheduled = false
        if (!isRunning || !hasDrawnAnyTexture) return@Runnable
        val nowNs = System.nanoTime()
        for (target in clientTargets) {
            if (!target.pacer.isDue(nowNs)) continue
            if (!settings.repeatFrames && target.lastTextureVersion == textureVersion) continue
            val startNs = System.nanoTime()
            // Every submission, including a static-frame repeat, shares the same monotonic clock.
            val timestamp = maxOf(startNs, target.lastPresentationNs + 1)
            val repeated = target.lastTextureVersion == textureVersion
            if (renderSingleTarget(target, timestamp)) {
                val elapsedNs = System.nanoTime() - startNs
                target.lastPresentationNs = timestamp
                target.lastTextureVersion = textureVersion
                synchronized(telemetryLock) {
                    target.telemetryFrames++
                    target.telemetryHandoffNs += elapsedNs
                    windowSubmissions++
                    windowHandoffNs += elapsedNs
                    totalSubmitted++
                    if (repeated) totalRepeated++
                    if (elapsedNs > 1_000_000_000L / target.pacer.fps) slowHandoffs++
                    handoffSamples[handoffSampleIndex] = elapsedNs
                    handoffSampleIndex = (handoffSampleIndex + 1) % handoffSamples.size
                    handoffSampleCount = minOf(handoffSampleCount + 1, handoffSamples.size)
                }
            } else {
                synchronized(telemetryLock) { totalFailures++ }
                target.failureCount++
                if (!target.surface.isValid || target.failureCount >= 30) {
                    clientTargets.remove(target)
                    destroyTargetEgl(target)
                    onSurfaceEvictedListener?.invoke(target.surface)
                    SunshineLogManager.log("DISPATCHER_WARN", "Evicted failing encoder surface; native session remains protected")
                }
            }
            // No backlog or catch-up burst after a slow encoder or a paused GL thread.
            target.pacer.advance(System.nanoTime())
        }
        restoreDummySurface()
        scheduleFrame()
    }

    private fun scheduleFrame() {
        val handler = glHandler ?: return
        if (!isRunning || !hasDrawnAnyTexture || frameScheduled) return
        val eligible = clientTargets.filter { settings.repeatFrames || it.lastTextureVersion != textureVersion }
        val dueNs = eligible.minOfOrNull { it.pacer.nextFrameNs } ?: return
        val delayNs = (dueNs - System.nanoTime()).coerceAtLeast(0)
        frameScheduled = handler.postDelayed(framePumpRunnable, (delayNs + 999_999L) / 1_000_000L)
    }

    private fun rescheduleFrame() {
        glHandler?.removeCallbacks(framePumpRunnable)
        frameScheduled = false
        scheduleFrame()
    }

    @Synchronized
    fun start(width: Int, height: Int): Surface {
        if (!isRunning && glThread != null) throw IllegalStateException("Previous GL dispatcher is still stopping")
        if (isRunning && inputSurface != null) return inputSurface!!
        sourceWidth = if (width > 0) width else 1920
        sourceHeight = if (height > 0) height else 1080

        val thread = HandlerThread("Sunshine-GL-Dispatcher")
        thread.start()
        glThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler

        val latch = CountDownLatch(1)
        var initError: Throwable? = null

        handler.post {
            try {
                initEgl()
                initGl()
                initSurfaceTexture(sourceWidth, sourceHeight)
                isRunning = true
                hasDrawnAnyTexture = false
                textureVersion = 0
                pendingTexture = false
                frameScheduled = false
                synchronized(telemetryLock) {
                    windowStartNs = System.nanoTime()
                    windowCaptures = 0
                    windowSubmissions = 0
                    windowHandoffNs = 0L
                    totalSubmitted = 0
                    totalRepeated = 0
                    totalCoalesced = 0
                    totalFailures = 0
                    slowHandoffs = 0
                    lastCaptureNs = 0
                    handoffSampleCount = 0
                    handoffSampleIndex = 0
                }
                SunshineLogManager.log("DISPATCHER", "SunshineFrameDispatcher initialized (${width}x$height)")
            } catch (t: Throwable) {
                initError = t
                SunshineLogManager.log("DISPATCHER_ERR", "Failed to start SunshineFrameDispatcher: ${t.message}")
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(3, TimeUnit.SECONDS)) {
            stop()
            throw IllegalStateException("GL dispatcher initialization timed out")
        }
        initError?.let { stop(); throw RuntimeException("Failed to initialize SunshineFrameDispatcher", it) }
        return inputSurface ?: throw IllegalStateException("Input surface is null")
    }

    /** Resize the producer without recreating MediaProjection or any encoder surfaces. */
    fun resizeCaptureSync(width: Int, height: Int): Surface? {
        val handler = glHandler ?: return null
        val latch = CountDownLatch(1)
        var resizedSurface: Surface? = null
        val resize = Runnable {
            try {
                if (isRunning && width > 0 && height > 0) {
                    // Some OEM producers keep the old buffer geometry after VD.resize().
                    // The caller detaches the producer first; encoder targets stay alive.
                    handler.removeCallbacks(framePumpRunnable)
                    handler.removeCallbacks(latchFrameRunnable)
                    frameScheduled = false
                    pendingTexture = false
                    hasDrawnAnyTexture = false
                    inputSurface?.release()
                    surfaceTexture?.release()
                    sourceWidth = width
                    sourceHeight = height
                    initSurfaceTexture(width, height)
                    resizedSurface = inputSurface
                }
            } catch (t: Throwable) {
                SunshineLogManager.log("DISPATCHER_ERR", "Capture resize failed: ${t.message}")
            } finally { latch.countDown() }
        }
        if (!handler.post(resize)) return null
        if (!latch.await(3, TimeUnit.SECONDS)) {
            handler.removeCallbacks(resize)
            return null
        }
        return resizedSurface
    }

    fun addSurface(surface: Surface, width: Int, height: Int, fps: Int = 60, threadId: Long = 0) {
        val handler = glHandler ?: return
        handler.post {
            if (!isRunning) return@post
            if (clientTargets.none { it.surface == surface }) {
                if (clientTargets.isEmpty()) synchronized(telemetryLock) {
                    // An idle period must not dilute the first measurement after reconnect.
                    windowStartNs = System.nanoTime()
                    windowCaptures = 0
                    windowSubmissions = 0
                    windowHandoffNs = 0L
                    latestMeasuredFps = 0.0
                    latestCaptureFps = 0.0
                    latestEncodeTimeMs = 0.0
                }
                val requestedFps = if (fps > 0) fps.coerceAtMost(240) else 60
                clientTargets.add(ClientTarget(surface, null, width, height, requestedFps,
                    FramePacer(minOf(requestedFps, settings.maxFps)), threadId))
                SunshineLogManager.log("DISPATCHER", "Added ${width}x$height @ ${requestedFps}fps encoder; active=${clientTargets.size}")
            }
            // A new client is immediately due, including when the captured screen is static.
            rescheduleFrame()
        }
    }

    private var onSurfaceEvictedListener: ((Surface) -> Unit)? = null

    fun setOnSurfaceEvictedListener(listener: ((Surface) -> Unit)?) {
        onSurfaceEvictedListener = listener
    }

    fun hasSurface(surface: Surface): Boolean {
        return clientTargets.any { it.surface == surface }
    }

    fun removeSurface(surface: Surface) {
        val handler = glHandler ?: return
        handler.post {
            val target = clientTargets.find { it.surface == surface }
            if (target != null) {
                clientTargets.remove(target)
                destroyTargetEgl(target)
                rescheduleFrame()
                SunshineLogManager.log("DISPATCHER", "Removed client stream surface. Remaining active clients: ${clientTargets.size}")
            }
        }
    }

    /**
     * Synchronously unbinds and destroys the target's EGLSurface on the GL thread
     * before returning to native code (which will immediately call AMediaCodec_stop).
     * This prevents EGL_BAD_SURFACE / BufferQueue abandonment races that could
     * otherwise disrupt remaining connected clients.
     */
    fun removeSurfaceSync(surface: Surface) {
        // Remove from CopyOnWriteArrayList immediately so no further frame loop iterates over it
        val target = clientTargets.find { it.surface == surface }
        if (target != null) {
            clientTargets.remove(target)
        }
        val handler = glHandler ?: return
        if (Thread.currentThread() == glThread) {
            if (target != null) {
                destroyTargetEgl(target)
            }
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try {
                if (target != null) {
                    destroyTargetEgl(target)
                } else {
                    val lateTarget = clientTargets.find { it.surface == surface }
                    if (lateTarget != null) {
                        clientTargets.remove(lateTarget)
                        destroyTargetEgl(lateTarget)
                    }
                }
                rescheduleFrame()
                SunshineLogManager.log("DISPATCHER", "Synchronously removed client stream surface. Remaining active clients: ${clientTargets.size}")
            } finally {
                latch.countDown()
            }
        }
        try {
            if (!latch.await(250, TimeUnit.MILLISECONDS)) {
                SunshineLogManager.log("DISPATCHER_WARN", "Encoder EGL teardown exceeded 250ms; inspect handoff diagnostics")
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    fun pruneDeadSurfaces() {
        val handler = glHandler ?: return
        handler.post {
            val deadList = clientTargets.filter { !it.surface.isValid }
            for (dead in deadList) {
                clientTargets.remove(dead)
                destroyTargetEgl(dead)
                SunshineLogManager.log("DISPATCHER", "Pruned invalid surface. Remaining: ${clientTargets.size}")
                try {
                    onSurfaceEvictedListener?.invoke(dead.surface)
                } catch (_: Exception) {}
            }
        }
    }

    fun clearSurfaces() {
        val handler = glHandler ?: return
        handler.post {
            for (target in clientTargets) {
                destroyTargetEgl(target)
            }
            clientTargets.clear()
            rescheduleFrame()
            SunshineLogManager.log("DISPATCHER", "Cleared all client stream surfaces")
        }
    }

    fun getActiveTargetCount(): Int = clientTargets.size

    @Synchronized
    fun stop() {
        if (stopping) return
        isRunning = false
        val thread = glThread ?: return
        stopping = true
        val handler = glHandler
        handler?.removeCallbacks(framePumpRunnable)
        handler?.removeCallbacks(latchFrameRunnable)
        handler?.post {
            if (glThread !== thread) return@post
            isRunning = false
            try {
                for (target in clientTargets) {
                    destroyTargetEgl(target)
                }
                clientTargets.clear()

                if (programId != 0) {
                    GLES20.glDeleteProgram(programId)
                    programId = 0
                }
                if (textureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                    textureId = 0
                }

                surfaceTexture?.setOnFrameAvailableListener(null)
                surfaceTexture?.release()
                surfaceTexture = null
                inputSurface?.release()
                inputSurface = null

                val dpy = eglDisplay
                if (dpy != null && dpy != EGL14.EGL_NO_DISPLAY) {
                    dummySurface?.let {
                        if (it != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, it)
                    }
                    dummySurface = null

                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    eglContext?.let {
                        if (it != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, it)
                    }
                    eglContext = null

                    EGL14.eglTerminate(dpy)
                    eglDisplay = null
                }
                SunshineLogManager.log("DISPATCHER", "SunshineFrameDispatcher stopped and resources released")
            } catch (e: RuntimeException) {
                SunshineLogManager.log("DISPATCHER_WARN", "GL resource teardown failed: ${e.message}")
            } finally {
                glHandler = null
                frameScheduled = false
                pendingTexture = false
                hasDrawnAnyTexture = false
                stopping = false
                glThread = null
                thread.quitSafely()
            }
        }
    }

    private fun initEgl() {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(dpy, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }
        eglDisplay = dpy

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID: required by encoder input surfaces.
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(dpy, attribList, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            throw RuntimeException("eglChooseConfig failed")
        }
        val cfg = configs[0] ?: throw RuntimeException("Null EGLConfig")
        eglConfig = cfg

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        val ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (ctx == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed")
        }
        eglContext = ctx

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        val dummy = EGL14.eglCreatePbufferSurface(dpy, cfg, pbufferAttribs, 0)
        if (dummy == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreatePbufferSurface failed")
        }
        dummySurface = dummy

        if (!EGL14.eglMakeCurrent(dpy, dummy, dummy, ctx)) {
            throw RuntimeException("eglMakeCurrent dummy failed")
        }
    }

    private fun initGl() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("GL program link error: $log")
        }
        programId = prog

        aPositionHandle = GLES20.glGetAttribLocation(prog, "aPosition")
        aTexCoordHandle = GLES20.glGetAttribLocation(prog, "aTextureCoord")
        uTextureMatrixHandle = GLES20.glGetUniformLocation(prog, "uTextureMatrix")
        uSamplerHandle = GLES20.glGetUniformLocation(prog, "sTexture")
    }

    private fun initSurfaceTexture(width: Int, height: Int) {
        val st = SurfaceTexture(textureId)
        st.setDefaultBufferSize(width, height)
        val surf = Surface(st)
        surfaceTexture = st
        inputSurface = surf

        val handler = glHandler ?: return
        st.setOnFrameAvailableListener({
            if (isRunning && surfaceTexture === st && !pendingTexture) {
                pendingTexture = true
                handler.post(latchFrameRunnable)
            }
        }, handler)
    }

    private fun restoreDummySurface() {
        val dpy = eglDisplay ?: return
        val ctx = eglContext ?: return
        val dummy = dummySurface ?: return
        if (dpy != EGL14.EGL_NO_DISPLAY && ctx != EGL14.EGL_NO_CONTEXT && dummy != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(dpy, dummy, dummy, ctx)
        }
    }

    private fun renderSingleTarget(target: ClientTarget, timestamp: Long): Boolean {
        val dpy = eglDisplay ?: return false
        val ctx = eglContext ?: return false
        val cfg = eglConfig ?: return false

        if (!target.surface.isValid) {
            return false
        }

        if (target.eglSurface == null || target.eglSurface == EGL14.EGL_NO_SURFACE) {
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            target.eglSurface = try {
                EGL14.eglCreateWindowSurface(dpy, cfg, target.surface, surfaceAttribs, 0)
            } catch (_: Throwable) {
                EGL14.EGL_NO_SURFACE
            }
            if (target.eglSurface == null || target.eglSurface == EGL14.EGL_NO_SURFACE) {
                EGL14.eglGetError()
                restoreDummySurface()
                return false
            }
        }

        val eglSurf = target.eglSurface ?: return false
        if (!EGL14.eglMakeCurrent(dpy, eglSurf, eglSurf, ctx)) {
            val err = EGL14.eglGetError()
            SunshineLogManager.log("DISPATCHER_WARN", "eglMakeCurrent failed (EGL error 0x${Integer.toHexString(err)})")
            destroyTargetEgl(target)
            return false
        }

        return try {
            val targetW = target.width
            val targetH = target.height
            val turn = CaptureGeometry.compatibilityTurn(sourceWidth, sourceHeight, targetW, targetH, sourceRotation, settings.rotationCompat && !sourceIsVirtual)
            val viewport = CaptureGeometry.orientedFit(sourceWidth, sourceHeight, targetW, targetH, turn)
            val textureCoords = when (turn) { 1 -> clockwiseCoords; 3 -> counterclockwiseCoords; else -> texCoordBuffer }
            // Clear every frame: rotation/resize may expose pixels from an older viewport.
            GLES20.glViewport(0, 0, targetW, targetH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
            GLES20.glUseProgram(programId)

            GLES20.glUniformMatrix4fv(uTextureMatrixHandle, 1, false, texMatrix, 0)

            GLES20.glEnableVertexAttribArray(aPositionHandle)
            GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(aTexCoordHandle)
            GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureCoords)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(uSamplerHandle, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionHandle)
            GLES20.glDisableVertexAttribArray(aTexCoordHandle)

            EGLExt.eglPresentationTimeANDROID(dpy, eglSurf, timestamp)
            val swapOk = EGL14.eglSwapBuffers(dpy, eglSurf)
            if (!swapOk) {
                val err = EGL14.eglGetError()
                SunshineLogManager.log("DISPATCHER_WARN", "eglSwapBuffers failed (EGL error 0x${Integer.toHexString(err)})")
                destroyTargetEgl(target)
            } else {
                target.failureCount = 0
            }
            swapOk
        } catch (_: Throwable) {
            destroyTargetEgl(target)
            false
        }
    }

    private fun destroyTargetEgl(target: ClientTarget) {
        val dpy = eglDisplay ?: return
        // Always rebind context to dummySurface BEFORE destroying any EGLSurface
        // so the destroyed surface is never left bound as the current surface.
        restoreDummySurface()
        val eglSurf = target.eglSurface
        if (eglSurf != null && eglSurf != EGL14.EGL_NO_SURFACE) {
            try {
                EGL14.eglDestroySurface(dpy, eglSurf)
            } catch (_: Throwable) {}
            target.eglSurface = null
        }
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compilation error: $log")
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            uniform mat4 uTextureMatrix;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTextureMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }
}
