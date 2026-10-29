package com.alkaidlab.sunshine.service

import android.content.Context

data class ServiceStartupSettings(val startOnBoot: Boolean = false, val keepAlive: Boolean = true) {
    companion object {
        fun read(context: Context): ServiceStartupSettings {
            val prefs = context.getSharedPreferences("service_startup", Context.MODE_PRIVATE)
            return ServiceStartupSettings(prefs.getBoolean("boot", false), prefs.getBoolean("keep_alive", true))
        }
    }
    fun save(context: Context): Boolean = context.getSharedPreferences("service_startup", Context.MODE_PRIVATE)
        .edit().putBoolean("boot", startOnBoot).putBoolean("keep_alive", keepAlive).commit()
}
