package com.alkaidlab.sunshine.service

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ServiceStartupSettingsTest {
    @Test fun upgradeDoesNotTurnOldServiceOnlyStartupIntoAutomaticRecording() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("service_startup", 0).edit().clear().putBoolean("boot", true).commit()
        val settings = ServiceStartupSettings.read(context)
        assertTrue(settings.startOnBoot)
        assertFalse(settings.instantConnect)
    }
    @Test fun explicitInstantConnectSurvivesSettingsReload() {
        val context = RuntimeEnvironment.getApplication()
        val settings = ServiceStartupSettings(startOnBoot = true, keepAlive = true, instantConnect = true)
        assertTrue(settings.save(context))
        assertEquals(settings, ServiceStartupSettings.read(context))
        assertTrue(settings.copy(startOnBoot = false, instantConnect = false).save(context))
        assertFalse(ServiceStartupSettings.read(context).instantConnect)
    }
}
