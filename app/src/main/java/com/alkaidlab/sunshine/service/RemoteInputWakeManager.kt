package com.alkaidlab.sunshine.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/** Remote input wakes the display; secure keyguard remains managed by Android. */
class RemoteInputWakeManager(context: Context, private val injectWakeKey: () -> Boolean) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val gate = RemoteWakeGate()
    private var remotePower: Any? = null
    private var wakeMethod: Method? = null

    @Synchronized fun reset() { remotePower = null; wakeMethod = null; gate.reset() }

    @Synchronized fun onInput(streaming: Boolean) {
        val manager = power ?: return
        if (!gate.shouldAttempt(streaming, manager.isInteractive, SystemClock.uptimeMillis())) return
        try {
            check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                "Shizuku permission or connection unavailable"
            }
            if (remotePower == null) {
                val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("power"))
                remotePower = Class.forName("android.os.IPowerManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                wakeMethod = remotePower!!.javaClass.methods.firstOrNull {
                    it.name == "wakeUp" && it.parameterTypes.contentEquals(arrayOf(
                        java.lang.Long.TYPE, java.lang.Integer.TYPE, String::class.java, String::class.java))
                } ?: error("Power service wakeUp interface unavailable")
            }
            wakeMethod!!.invoke(remotePower, SystemClock.uptimeMillis(), 2,
                "Sunshine:MoonlightInput", if (Shizuku.getUid() == 0) "android" else "com.android.shell")
            SunshineLogManager.log("REMOTE_WAKE", "Display wake requested by Moonlight input")
        } catch (e: Exception) {
            remotePower = null
            wakeMethod = null
            // OEMs may change the hidden power interface. The shell's WAKEUP key
            // follows Android's normal power/keyguard policy, without toggling sleep.
            if (injectWakeKey()) {
                SunshineLogManager.log("REMOTE_WAKE", "Display wake requested by Moonlight input (system wake key)")
            } else {
                SunshineLogManager.log("REMOTE_WAKE_ERR", (e.cause ?: e).message ?: e.javaClass.simpleName)
            }
        }
    }
}

/** Failed requests are rate limited; interactive and idle input never wake the display. */
internal class RemoteWakeGate {
    private var lastAttempt: Long? = null
    fun shouldAttempt(streaming: Boolean, interactive: Boolean, now: Long): Boolean {
        if (!streaming || interactive) return false
        val last = lastAttempt
        if (last != null && now >= last && now - last < 1000) return false
        lastAttempt = now
        return true
    }
    fun reset() { lastAttempt = null }
}
