package com.alkaidlab.sunshine.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.alkaidlab.sunshine.engine.NativeServer
import com.alkaidlab.sunshine.engine.InputBroker

/** Only this disposable process loads JNI. Capture, API, and UI remain in the host. */
class SunshineEngineService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false
    private var audioStarted = false
    private var restartPending = false
    override fun onCreate() {
        super.onCreate()
        // A foreground host binding alone does not give this separate process
        // its own foreground-service state on Samsung screen-off policies.
        // Share the existing server notification instead of adding a second one.
        val notification = androidx.core.app.NotificationCompat.Builder(this, "sunshine_service_channel")
            .setContentTitle("Sunshine 串流服务")
            .setContentText("串流引擎运行中")
            .setSmallIcon(com.alkaidlab.sunshine.R.mipmap.ic_launcher)
            .setContentIntent(android.app.PendingIntent.getActivity(this, 0,
                Intent(this, com.alkaidlab.sunshine.ui.MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            // Both foreground services share this notification ID. Either writer
            // must preserve the escape action when it replaces the other content.
            .addAction(0, "退出桌面 / 恢复主屏", android.app.PendingIntent.getService(this, 2,
                Intent(this, SunshineService::class.java).setAction(SunshineService.ACTION_EXIT_DESKTOP),
                android.app.PendingIntent.FLAG_IMMUTABLE))
            .build()
        startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        // Native input callbacks run here, so the mapper needs this process's
        // actual physical display dimensions as well as the host capture size.
        InputBroker.setContext(this)
    }
    private val binder = object : ISunshineEngine.Stub() {
        override fun start(host: ISunshineEngineHost, name: String, cert: String, key: String,
                           state: String, hevc: Boolean) {
            synchronized(SunshineEngineBridge.lock) {
                if (started) return
                started = true
                SunshineEngineBridge.host = host
                host.asBinder().linkToDeath({ terminate() }, 0)
                NativeServer.setSunshineName(name)
                NativeServer.setCertPath(cert)
                NativeServer.setPkeyPath(key)
                NativeServer.setFileStatePath(state)
                if (hevc) NativeServer.enableH265()
                Thread({
                    try {
                        NativeServer.start()
                    } finally {
                        // Native globals cannot safely be initialized a second time.
                        terminate()
                    }
                }, "Sunshine-Native").start()
            }
        }

        override fun restartIfIdle(revision: Long): Boolean = synchronized(SunshineEngineBridge.lock) {
            if (restartPending || !SunshineEngineBridge.sessions.canRestart(revision)) return false
            restartPending = true
            mainHandler.postDelayed({
                synchronized(SunshineEngineBridge.lock) {
                    restartPending = false
                    // Recheck in the engine, including connections whose host callback is pending.
                    if (SunshineEngineBridge.sessions.canRestart(revision)) terminate()
                }
            }, 100)
            true
        }

        override fun shutdown() { mainHandler.post { terminate() } }
        // Do not take the host callback lock: configuration can arrive inside streamStarted.
        override fun configureVideo(minimum: Int, maximum: Int, maxFps: Int) {
            NativeServer.configureVideo(minimum, maximum, maxFps)
        }
        override fun configureCanvas(sourceWidth: Int, sourceHeight: Int, followOrientation: Boolean, rotationCompat: Boolean, sourceDisplayId: Int, sourceFps: Int) {
            com.alkaidlab.sunshine.engine.InputBroker.setSourceDisplay(sourceDisplayId, sourceWidth, sourceHeight)
            com.alkaidlab.sunshine.engine.InputBroker.setRotationCompatibility(rotationCompat)
            NativeServer.configureCanvas(sourceWidth, sourceHeight, followOrientation, sourceFps)
        }
        override fun configureEncoders(avc: String, hevc: String, avcNames: Array<out String>, hevcNames: Array<out String>) {
            NativeServer.configureEncoders(avc, hevc, avcNames, hevcNames)
        }
        override fun videoSnapshot(): String = NativeServer.videoSnapshot()
        override fun controlVideo(sessionId: Long, operation: Int, value: Int): Boolean =
            NativeServer.controlVideo(sessionId, operation, value)
        override fun submitPin(pin: String) { NativeServer.submitPin(pin) }
        override fun startAudio(samplesPerPacket: Int) {
            synchronized(SunshineEngineBridge.lock) {
                if (audioStarted) return
                NativeServer.startAudioRecording(EngineAudioSource(), samplesPerPacket)
                audioStarted = true
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onUnbind(intent: Intent): Boolean { terminate(); return false }
    override fun onDestroy() { stopForeground(STOP_FOREGROUND_DETACH); terminate(); super.onDestroy() }

    private fun terminate() {
        // Never call exitServer() on the application's main thread; JNI may block or exit.
        Process.killProcess(Process.myPid())
    }
}

/** JNI looks up read([FIII)I on the supplied object's class. */
class EngineAudioSource {
    fun read(buffer: FloatArray, offset: Int, count: Int, mode: Int): Int {
        val samples = try {
            SunshineEngineBridge.host?.readAudio(count, mode)
        } catch (_: android.os.RemoteException) { null }
        if (samples == null || samples.isEmpty()) {
            // Avoid the native read-error busy loop when capture is unavailable.
            android.os.SystemClock.sleep(10)
            return 0
        }
        val size = minOf(samples.size, count, buffer.size - offset)
        samples.copyInto(buffer, offset, 0, size)
        return size
    }
}
