package com.alkaidlab.sunshine.service

import android.content.Context

data class ServiceStartupSettings(val startOnBoot: Boolean = false, val keepAlive: Boolean = true,
                                 val instantConnect: Boolean = false) {
    companion object {
        fun read(context: Context): ServiceStartupSettings {
            val prefs = context.getSharedPreferences("service_startup", Context.MODE_PRIVATE)
            return ServiceStartupSettings(prefs.getBoolean("boot", false), prefs.getBoolean("keep_alive", true),
                prefs.getBoolean("instant_connect", false))
        }
    }
    fun save(context: Context): Boolean = context.getSharedPreferences("service_startup", Context.MODE_PRIVATE)
        .edit().putBoolean("boot", startOnBoot).putBoolean("keep_alive", keepAlive)
        .putBoolean("instant_connect", instantConnect).commit()
}
