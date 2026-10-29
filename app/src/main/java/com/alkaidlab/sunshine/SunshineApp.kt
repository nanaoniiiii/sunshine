package com.alkaidlab.sunshine

import android.app.Application
import com.alkaidlab.sunshine.service.SunshineLogManager
import com.alkaidlab.sunshine.service.SunshineRuntimeManager
import rikka.shizuku.Shizuku

class SunshineApp : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN (20) is a normal app switch, not low memory. Clearing there
        // evicted every desktop icon whenever a launched app covered HOME.
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND)
            com.alkaidlab.sunshine.ui.desktop.DesktopArtworkCache.clear()
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        SunshineLogManager.log("SHIZUKU", "Shizuku binder received")
        SunshineRuntimeManager.getInstance().initialize(this)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        SunshineLogManager.log("SHIZUKU", "Shizuku binder dead")
        SunshineRuntimeManager.getInstance().onShizukuDisconnected()
    }

    override fun onCreate() {
        super.onCreate()
        if (getProcessName().endsWith(":engine")) {
            com.alkaidlab.sunshine.engine.InputBroker.setContext(this)
            return
        }
        SunshineRuntimeManager.getInstance().initialize(this)

        com.alkaidlab.sunshine.update.AppUpdater.initialize(this)
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (e: Exception) {
            SunshineLogManager.log("SHIZUKU_WARN", "Error registering Shizuku listeners: ${e.message}")
        }
    }
}
