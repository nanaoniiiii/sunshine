package com.alkaidlab.sunshine.service

import com.alkaidlab.sunshine.data.*
import org.junit.Assert.*
import org.junit.Test

class AppScopeTest {
    @Test fun independentConnectionsOnlyEndTheirOwnGate() {
        val a = SingleAppGate("app.first", 0)
        val b = SingleAppGate("app.second", 0)
        assertFalse(a.shouldEnd("app.first", 100))
        assertFalse(b.shouldEnd("app.second", 100))
        assertFalse(a.shouldEnd("app.second", 500))
        assertTrue(a.shouldEnd("app.second", 1001))
        assertFalse(b.shouldEnd("app.second", 1001))
    }
    @Test fun overlaysAndUnknownSamplesDoNotMeanAppExit() {
        val gate = SingleAppGate("app.target", 0)
        assertFalse(gate.shouldEnd("app.target", 0))
        assertFalse(gate.shouldEnd(null, 10000))
        assertFalse(gate.shouldEnd("app.other", 10100))
        assertFalse(gate.shouldEnd(null, 10300))
        assertFalse(gate.shouldEnd("app.target", 20000))
    }
    @Test fun startupGraceAndBriefAppTransitionsAreDebounced() {
        val gate = SingleAppGate("app.target", 0)
        assertFalse(gate.shouldEnd("app.home", 9000))
        assertFalse(gate.shouldEnd("app.target", 9100))
        assertFalse(gate.shouldEnd("app.home", 10000))
        assertFalse(gate.shouldEnd("app.target", 10300))
        assertFalse(gate.shouldEnd("app.home", 11000))
        assertTrue(gate.shouldEnd("app.home", 11501))
    }
    @Test fun floatingPipAndSecondaryDisplaysAreIgnored() {
        val dump = """
Display #0 (activities from top to bottom):
  Stack #4: type=standard mode=freeform
    mResumedActivity: ActivityRecord{a u0 popup.app/.Main t4}
  Stack #3: type=standard mode=pinned
    mResumedActivity: ActivityRecord{b u0 video.pip/.Main t3}
  Stack #2: type=standard mode=fullscreen
    mResumedActivity: ActivityRecord{c u0 app.target/.Main t2}
Display #1 (activities from top to bottom):
  Stack #9: type=standard mode=fullscreen
    mResumedActivity: ActivityRecord{d u0 other.display/.Main t9}
""".trimIndent()
        assertEquals("app.target", ForegroundAppParser.fullscreenPackage(dump))
        assertNull(ForegroundAppParser.fullscreenPackage(dump.replace("mode=fullscreen", "mode=freeform")))
    }
    @Test fun homeIsRealExitButSleepingActivitiesAreNotResumed() {
        assertEquals("com.oppo.launcher", ForegroundAppParser.fullscreenPackage("Display #0\n  Stack #1: type=home mode=fullscreen\n mResumedActivity: ActivityRecord{x u0 com.oppo.launcher/.Launcher t1}"))
        assertNull(ForegroundAppParser.fullscreenPackage("Display #0\n  Stack #1: type=home mode=fullscreen\n mLastPausedActivity: ActivityRecord{x u0 app.target/.Main t1}"))
    }
    @Test fun catalogMigrationPreservesIdsAndNeverReusesDeletedIds() {
        val old = AppsResponse(apps = listOf(AppItem(name = "Desktop"), AppItem(name = "Settings", cmd = "com.android.settings")))
        val current = AppCatalog.normalize(old)
        assertEquals("1", current.apps[0].id)
        assertEquals("2", current.apps[1].id)
        assertEquals(3, current.nextId)
        val deleted = current.copy(apps = current.apps.take(1))
        assertEquals(3, AppCatalog.normalize(deleted).nextId)
        assertEquals(current, AppCatalog.normalize(current))
    }
}
