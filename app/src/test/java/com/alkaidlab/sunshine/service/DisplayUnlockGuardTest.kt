package com.alkaidlab.sunshine.service

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DisplayUnlockGuardTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val keyguard get() = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    @Test fun trustedDeviceWithOccludedKeyguardCanCreateDesktop() {
        shadowOf(keyguard).setIsDeviceSecure(true)
        shadowOf(keyguard).setKeyguardLocked(true)
        shadowOf(keyguard).setIsDeviceLocked(false)
        assertTrue(keyguard.isKeyguardLocked)
        assertFalse(keyguard.isDeviceLocked)
        requireDisplayUserUnlocked(context, "unlock required")
    }

    @Test fun deviceRequiringAuthenticationStillFailsWithActionMessage() {
        shadowOf(keyguard).setKeyguardLocked(true)
        shadowOf(keyguard).setIsDeviceLocked(true)
        val error = assertThrows(IllegalStateException::class.java) {
            requireDisplayUserUnlocked(context, "unlock before desktop")
        }
        assertEquals("unlock before desktop", error.message)
    }

    @Test fun deviceRelockIsRecheckedForEachOperation() {
        shadowOf(keyguard).setKeyguardLocked(false)
        shadowOf(keyguard).setIsDeviceLocked(false)
        requireDisplayUserUnlocked(context, "unlock required")
        shadowOf(keyguard).setIsDeviceLocked(true)
        assertThrows(IllegalStateException::class.java) {
            requireDisplayUserUnlocked(context, "unlock required")
        }
    }
}
