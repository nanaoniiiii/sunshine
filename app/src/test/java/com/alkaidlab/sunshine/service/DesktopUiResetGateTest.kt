package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class DesktopUiResetGateTest {
    @Test fun exitResetsOnceAfterPhysicalCaptureFinishesRestoring() {
        val gate = DesktopUiResetGate()
        assertFalse(gate.update(true, false, "desktop"))
        assertFalse(gate.update(true, true, "desktop"))
        assertFalse(gate.update(false, true, "desktop"))
        assertTrue(gate.update(false, false, "desktop"))
        repeat(10) { assertFalse(gate.update(false, false, "desktop")) }
    }
    @Test fun initialIdleAndFailedCreationDoNotRecreateUi() {
        val gate = DesktopUiResetGate()
        assertFalse(gate.update(false, false, "desktop"))
        assertFalse(gate.update(false, true, "desktop"))
        assertFalse(gate.update(false, false, "desktop"))
    }
    @Test fun primaryScreenOffNeverCountsAsDesktopExit() {
        val gate = DesktopUiResetGate()
        assertFalse(gate.update(true, false, "screen_off"))
        assertFalse(gate.update(false, false, "desktop"))
    }
    @Test fun repeatedDexVisitsEachResetOnce() {
        val gate = DesktopUiResetGate()
        repeat(3) {
            assertFalse(gate.update(true, false, "desktop"))
            assertTrue(gate.update(false, false, "desktop"))
        }
    }
}
