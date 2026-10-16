package com.alkaidlab.sunshine.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.Build

/** Holds power resources only while at least one client is streaming. Called on the main thread. */
class StreamingPowerManager(context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var wifiLock: WifiManager.WifiLock? = null
    private var screenOffWifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Session lifetime, not a timer: release on last disconnect and service destruction.
    @android.annotation.SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    fun update(active: Boolean, lowLatency: Boolean) {
        if (!active) { release(); return }
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sunshine:Streaming")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            if (lowLatency) {
                // Android 10–13 low-latency locks are ignored when the physical
                // screen is off. Hold HIGH_PERF as the documented fallback;
                // LOW_LATENCY wins while eligible. Android 14+ remaps HIGH_PERF
                // to LOW_LATENCY, so a duplicate lock cannot remove that limit.
                if (Build.VERSION.SDK_INT < 34 && screenOffWifiLock?.isHeld != true) {
                    screenOffWifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Sunshine:ScreenOffWifi")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
                if (wifiLock?.isHeld != true) {
                    wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Sunshine:LowLatency")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            } else {
                wifiLock?.let { if (it.isHeld) it.release() }
                screenOffWifiLock?.let { if (it.isHeld) it.release() }
                wifiLock = null
                screenOffWifiLock = null
            }
        } catch (e: RuntimeException) {
            SunshineLogManager.log("POWER_WARN", "Streaming power request failed: ${e.message}")
        }
    }

    fun release() {
        wifiLock?.let { if (it.isHeld) it.release() }
        screenOffWifiLock?.let { if (it.isHeld) it.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        screenOffWifiLock = null
        wakeLock = null
    }
}
