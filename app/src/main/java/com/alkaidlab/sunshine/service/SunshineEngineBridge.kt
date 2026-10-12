package com.alkaidlab.sunshine.service

import android.os.Process
import android.os.RemoteException
import android.view.MotionEvent
import android.view.Surface
import com.connect_screen.mirror.job.SunshineMouse

/** JNI callbacks run in :engine. Keep start/stop/restart ordering under one lock. */
object SunshineEngineBridge {
    internal val lock = Any()
    internal val sessions = EngineSessions()
    private val clientDimensions = linkedMapOf<Long, Pair<Int, Int>>()
    @Volatile var host: ISunshineEngineHost? = null

    @JvmStatic fun streamStarted(width: Int, height: Int, fps: Int, packetDuration: Int,
                                 surface: Surface, audio: Boolean) {
        synchronized(lock) {
            val threadId = Process.myTid().toLong()
            sessions.started(threadId)
            clientDimensions[threadId] = width to height
            val capture = host?.streamStarted(threadId, width, height, fps, packetDuration, surface, audio)
            SunshineMouse.setCaptureDimensions(capture?.getOrNull(0) ?: width, capture?.getOrNull(1) ?: height)
            SunshineMouse.setActiveClientDimensions(width, height)
        }
    }

    @JvmStatic fun streamStopped() {
        synchronized(lock) {
            val threadId = Process.myTid().toLong()
            val revision = sessions.stopped(threadId) ?: return
            clientDimensions.remove(threadId)
            clientDimensions.values.lastOrNull()?.let { (width, height) ->
                SunshineMouse.setActiveClientDimensions(width, height)
            } ?: SunshineMouse.reset()
            host?.streamStopped(threadId, revision)
        }
    }

    @JvmStatic fun pinRequested() { host?.pinRequested() }
    @JvmStatic fun clientDiscovered(client: String) { host?.clientDiscovered(client) }
    @JvmStatic fun serverUuid(uuid: String) { host?.serverUuid(uuid) }
    @JvmStatic fun encoderError(error: String) {
        host?.encoderError(error)
        streamStopped()
    }

    private var lastInputCallback = 0L
    @JvmStatic @Synchronized fun remoteInput() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastInputCallback < 250) return
        lastInputCallback = now
        try { host?.remoteInput() } catch (_: RemoteException) {}
    }

    fun injectMotion(event: MotionEvent): Boolean {
        val callback = host ?: return false
        try { callback.motionEvent(event) } catch (_: RemoteException) {}
        return true
    }

    fun injectKeyboard(keyCode: Int, down: Boolean, modifiers: Int): Boolean {
        val callback = host ?: return false
        try { callback.keyboardEvent(keyCode, down, modifiers) } catch (_: RemoteException) {}
        return true
    }
}
