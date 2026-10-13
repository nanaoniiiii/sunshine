package com.alkaidlab.sunshine.service

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

class AppSessionGuard(private val context: Context, private val disconnect: (Long) -> Boolean) {
    private data class Watch(val gate: SingleAppGate, var unavailableSince: Long? = null)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val watches = ConcurrentHashMap<Long, Watch>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    init {
        scope.launch {
            while (isActive) {
                if (watches.isEmpty()) signal.receive()
                delay(500)
                if (watches.isEmpty() || !power.isInteractive) continue
                val now = SystemClock.elapsedRealtime()
                val dump = PrivilegedShell.run(arrayOf("dumpsys", "activity", "activities"))
                val foreground = dump?.let(ForegroundAppParser::fullscreenPackage)
                for ((thread, watch) in watches.entries.toList()) {
                    val lostMonitoring = if (dump == null) {
                        val since = watch.unavailableSince ?: now.also { watch.unavailableSince = it }
                        now - since >= 5000
                    } else { watch.unavailableSince = null; false }
                    if (watches[thread] !== watch) continue
                    if (lostMonitoring || watch.gate.shouldEnd(foreground, now)) {
                        if (disconnect(thread)) {
                            watches.remove(thread, watch)
                            SunshineLogManager.log("APP_SCOPE", if (lostMonitoring) "Restricted session ended because foreground monitoring was unavailable" else "Restricted session ended after leaving its application")
                        }
                    }
                }
            }
        }
    }
    fun attach(threadId: Long, packageName: String) {
        watches[threadId] = Watch(SingleAppGate(packageName, SystemClock.elapsedRealtime()))
        signal.trySend(Unit)
    }
    fun detach(threadId: Long) { watches.remove(threadId) }
    fun clear() { watches.clear() }
}
