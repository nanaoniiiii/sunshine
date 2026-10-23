package com.alkaidlab.sunshine.service

import android.app.KeyguardManager
import android.content.Context

/** Require authentication only when Android says this user's apps are locked. */
internal fun requireDisplayUserUnlocked(context: Context, message: String) {
    val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    // isKeyguardLocked also stays true for a trusted/swipe-dismissible keyguard
    // occluded by MainActivity.setShowWhenLocked(true). That is not a device lock.
    check(!keyguard.isDeviceLocked) { message }
}
