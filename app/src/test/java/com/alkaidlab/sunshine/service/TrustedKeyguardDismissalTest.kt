package com.alkaidlab.sunshine.service

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TrustedKeyguardDismissalTest {
    private lateinit var activity: Activity
    private lateinit var keyguard: KeyguardManager
    private val dismiss = TrustedKeyguardDismissal()

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        SunshineLogManager.clear()
    }

    @Test fun trustedOccludedLockScreenUsesSystemDismissal() {
        shadowOf(keyguard).setKeyguardLocked(true)
        shadowOf(keyguard).setIsDeviceLocked(false)
        dismiss.onForeground(activity)
        shadowOf(keyguard).setKeyguardLocked(false)
        assertTrue(SunshineLogManager.getRecentLogs().any { it.endsWith("Trusted lock screen dismissed") })
    }

    @Test fun authenticationRequiredDoesNotRequestDismissal() {
        shadowOf(keyguard).setKeyguardLocked(true)
        shadowOf(keyguard).setIsDeviceLocked(true)
        dismiss.onForeground(activity)
        shadowOf(keyguard).setKeyguardLocked(false)
        assertTrue(SunshineLogManager.getRecentLogs().isEmpty())
    }

    @Test fun alreadyDismissedLockScreenDoesNotRequestAgain() {
        shadowOf(keyguard).setKeyguardLocked(false)
        shadowOf(keyguard).setIsDeviceLocked(false)
        dismiss.onForeground(activity)
        assertTrue(SunshineLogManager.getRecentLogs().isEmpty())
    }

    @Test fun repeatedFocusWhilePendingKeepsOneRequest() {
        shadowOf(keyguard).setKeyguardLocked(true)
        shadowOf(keyguard).setIsDeviceLocked(false)
        dismiss.onForeground(activity)
        dismiss.onForeground(activity)
        shadowOf(keyguard).setKeyguardLocked(false)
        assertEquals(1, SunshineLogManager.getRecentLogs().size)
        assertTrue(SunshineLogManager.getRecentLogs().single().endsWith("Trusted lock screen dismissed"))
    }
}
