package com.alkaidlab.sunshine.service

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context

/** Close a swipe-dismissible/trusted lock screen through the normal Activity API. */
internal class TrustedKeyguardDismissal {
    private var pending = false

    fun onForeground(activity: Activity) {
        if (pending || activity.isFinishing || activity.isDestroyed) return
        val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isKeyguardLocked || keyguard.isDeviceLocked) return
        pending = true
        try {
            keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = complete("Trusted lock screen dismissed")
                override fun onDismissCancelled() = complete("Lock screen dismissal cancelled")
                override fun onDismissError() = complete("Lock screen dismissal unavailable")
            })
        } catch (error: RuntimeException) {
            complete("Lock screen dismissal unavailable: ${error.javaClass.simpleName}")
        }
    }

    private fun complete(message: String) {
        pending = false
        SunshineLogManager.log("KEYGUARD", message)
    }
}
