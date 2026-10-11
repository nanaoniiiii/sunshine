package com.alkaidlab.sunshine.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/** Holds power resources only while at least one client is streaming. Called on the main thread. */
class StreamingPowerManager(context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Session lifetime, not a timer: release on last disconnect and service destruction.
    @android.annotation.SuppressLint("WakelockTimeout")
    fun update(active: Boolean, lowLatency: Boolean) {
        if (!active) { release(); return }
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sunshine:Streaming")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (lowLatency && wifiLock?.isHeld != true) {
                wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Sunshine:LowLatency")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } else if (!lowLatency) {
                wifiLock?.let { if (it.isHeld) it.release() }
                wifiLock = null
            }
        } catch (e: RuntimeException) {
            SunshineLogManager.log("POWER_WARN", "Streaming power request failed: ${e.message}")
        }
    }

    fun release() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock = null
    }
}
