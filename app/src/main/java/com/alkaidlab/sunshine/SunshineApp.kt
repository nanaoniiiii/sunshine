package com.alkaidlab.sunshine

import android.app.Application
import com.alkaidlab.sunshine.service.SunshineLogManager
import com.alkaidlab.sunshine.service.SunshineRuntimeManager
import rikka.shizuku.Shizuku

class SunshineApp : Application() {

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

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (e: Exception) {
            SunshineLogManager.log("SHIZUKU_WARN", "Error registering Shizuku listeners: ${e.message}")
        }
    }
}
