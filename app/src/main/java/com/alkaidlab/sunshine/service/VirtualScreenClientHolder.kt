package com.alkaidlab.sunshine.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the lazily-created [VirtualScreenClient] behind the virtual screen.
 *
 * Roadmap #6 (virtual-screen extraction) step 1: moves only the client field
 * plus the thin delegating suspend functions out of [SunshineRuntimeManager].
 * All manager state is read through [Host] at call time, so evaluation timing
 * is unchanged. Behaviour-preserving; the manager's public API is untouched.
 */
internal class VirtualScreenClientHolder(private val host: Host) {

    internal interface Host {
        fun hostContext(): Context?
        fun isVirtualScreenActive(): Boolean
        fun onClientDisconnected()
    }

    private var client: VirtualScreenClient? = null

    /** Lazily creates the client on first use; matches the old displayClient(). */
    fun client(): VirtualScreenClient {
        return client ?: VirtualScreenClient(checkNotNull(host.hostContext())) {
            host.onClientDisconnected()
        }.also { client = it }
    }

    fun peek(): VirtualScreenClient? = client

    fun closeQuietly() {
        try { client?.close() } catch (_: Exception) {}
    }

    fun releaseAndClear() {
        try { client?.release() } catch (_: Exception) {}
        client = null
    }

    suspend fun launchApp(component: String): Boolean = withContext(Dispatchers.IO) {
        if (!host.isVirtualScreenActive()) false else client?.launch(component) == true
    }

    suspend fun tasksJson(): String = withContext(Dispatchers.IO) {
        if (!host.isVirtualScreenActive()) "[]" else client?.tasks() ?: "[]"
    }

    suspend fun action(action: String, taskId: Int = -1): Boolean = withContext(Dispatchers.IO) {
        if (!host.isVirtualScreenActive()) false else client?.action(action, taskId) == true
    }
}
