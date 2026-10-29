package com.alkaidlab.sunshine.service

import android.app.Application
import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DisplayPreferencesTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        for (name in listOf("stream_display", "service_startup", "fullscreen_display_restore"))
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        while (shadowOf(app).nextStartedService != null) { }
    }
    @Test fun choiceSurvivesNewStoreAndDisplayJournalCleanup() {
        assertEquals(StreamDisplayMode.DEFAULT, StreamDisplayPreferences(app).read())
        assertTrue(StreamDisplayPreferences(app).write(StreamDisplayMode.FULLSCREEN))
        app.getSharedPreferences("fullscreen_display_restore", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(StreamDisplayMode.FULLSCREEN, StreamDisplayPreferences(app).read())
        assertTrue(StreamDisplayPreferences(app).write(StreamDisplayMode.DEFAULT))
        assertEquals(StreamDisplayMode.DEFAULT, StreamDisplayPreferences(app).read())
    }
    @Test fun invalidStoredModeFallsBackToDefault() {
        app.getSharedPreferences("stream_display", Context.MODE_PRIVATE).edit().putString("mode", "bad").commit()
        assertEquals(StreamDisplayMode.DEFAULT, StreamDisplayPreferences(app).read())
        assertNull(StreamDisplayMode.parse("bad"))
    }
    @Test fun bootIsOptInAndRecoveryRetainsPreviousDefault() {
        assertFalse(ServiceStartupSettings.read(app).startOnBoot)
        assertTrue(ServiceStartupSettings.read(app).keepAlive)
        ServiceBootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
    }
    @Test fun enabledBootStartsOnlyHostWithoutCaptureToken() {
        assertTrue(ServiceStartupSettings(true, true).save(app))
        ServiceBootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val started = shadowOf(app).nextStartedService
        assertEquals(SunshineService::class.java.name, started.component?.className)
        assertFalse(started.hasExtra(SunshineService.EXTRA_RESULT_DATA))
    }
    @Test fun unrelatedBroadcastDoesNotStartHostAndDisabledBootSurvivesReload() {
        ServiceStartupSettings(true, false).save(app)
        ServiceBootReceiver().onReceive(app, Intent("other"))
        assertNull(shadowOf(app).nextStartedService)
        ServiceStartupSettings(false, false).save(app)
        assertEquals(ServiceStartupSettings(false, false), ServiceStartupSettings.read(app))
    }
}
