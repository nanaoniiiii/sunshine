package com.alkaidlab.sunshine.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the host; capture bootstrap can reuse a separately authorized AppOps grant. */
class ServiceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (!ServiceStartupSettings.read(context).startOnBoot || SunshineService.isRunning) return
        try {
            SunshineService.start(context, automaticCapture = true)
            SunshineLogManager.log("BOOT", "Host service requested after ${intent.action}; checking existing background capture authorization")
        } catch (e: RuntimeException) {
            // Android/OEM background restrictions must not turn boot into a crash loop.
            SunshineLogManager.log("BOOT", "Background startup unavailable (${e.javaClass.simpleName}); open Sunshine to start")
        }
    }
}
