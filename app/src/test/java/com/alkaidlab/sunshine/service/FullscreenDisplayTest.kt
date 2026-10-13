package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class FullscreenDisplayTest {
    private class Display : FullscreenDisplay.Backend {
        var overrideSize: FullscreenDisplay.Size? = null
        var angle = 0
        var syncWorks = true
        var commandWorks = true
        var fixedRotation = false
        var portraitActivity = false
        var onSet: () -> Unit = {}
        val rotations = mutableListOf<Pair<Int, Boolean>>()
        val commands = mutableListOf<FullscreenDisplay.Size?>()
        val sessions = linkedSetOf(101L, 202L)
        override fun snapshot() = FullscreenDisplay.Snapshot(overrideSize, angle, fixedRotation = fixedRotation)
        override fun rotation() = angle
        override fun setFixedRotation(enabled: Boolean): Boolean { fixedRotation = enabled; return true }
        override fun setRotation(rotation: Int, autoRotate: Boolean): Boolean {
            rotations += rotation to autoRotate
            angle = rotation
            return true
        }
        override fun setOverride(size: FullscreenDisplay.Size?): Boolean {
            commands += size
            overrideSize = size
            onSet()
            return commandWorks
        }
        override fun awaitDisplay(size: FullscreenDisplay.Size) = overrideSize?.let {
            FullscreenDisplay.baseSize(it, if (portraitActivity && !fixedRotation) 0 else angle) == size
        } == true
        override fun syncCapture() = syncWorks
    }
    @Test fun chooseDifferentClientsWithoutChangingTheirSessionIdentities() {
        val display = Display()
        assertTrue(FullscreenDisplay.apply(FullscreenDisplay.Size(3168, 1440), display) { 101L in display.sessions }.applied)
        assertTrue(FullscreenDisplay.apply(FullscreenDisplay.Size(1920, 1080), display) { 202L in display.sessions }.applied)
        assertEquals(setOf(101L, 202L), display.sessions)
        assertEquals(FullscreenDisplay.Size(1080, 1920), display.overrideSize)
    }
    @Test fun landscapeRotationUsesUnrotatedWmDimensions() {
        val display = Display().apply { angle = 1 }
        assertTrue(FullscreenDisplay.apply(FullscreenDisplay.Size(3168, 1440), display) { true }.applied)
        assertEquals(FullscreenDisplay.Size(1440, 3168), display.overrideSize)
    }
    @Test fun staleButtonDoesNotChangeTheDisplay() {
        val display = Display()
        assertFalse(FullscreenDisplay.apply(FullscreenDisplay.Size(1920, 1080), display) { false }.applied)
        assertTrue(display.commands.isEmpty())
    }
    @Test fun clientDisconnectingDuringSwitchRestoresPhysicalDefault() {
        val display = Display().apply { onSet = { sessions.remove(101L) } }
        assertFalse(FullscreenDisplay.apply(FullscreenDisplay.Size(1920, 1080), display) { 101L in display.sessions }.applied)
        assertNull(display.overrideSize)
        assertEquals(setOf(202L), display.sessions)
    }
    @Test fun captureFailureRestoresTheExistingOverride() {
        val original = FullscreenDisplay.Size(1440, 3200)
        val display = Display().apply { overrideSize = original; syncWorks = false }
        assertFalse(FullscreenDisplay.apply(FullscreenDisplay.Size(1920, 1080), display) { true }.applied)
        assertEquals(original, display.overrideSize)
    }
    @Test fun shellFailureStillAttemptsRollback() {
        val display = Display().apply { commandWorks = false }
        assertFalse(FullscreenDisplay.apply(FullscreenDisplay.Size(1920, 1080), display) { true }.applied)
        assertEquals(listOf(FullscreenDisplay.Size(1080, 1920), null), display.commands)
    }
    @Test fun oemChangingRotationIsRecheckedBeforeSuccess() {
        val display = Display().apply { onSet = { angle = 0 } }
        // Simulate an OEM changing orientation despite the initial rotation lock.
        var first = true
        display.onSet = { if (first) { display.angle = 0; first = false } }
        assertTrue(FullscreenDisplay.apply(FullscreenDisplay.Size(3168, 1440), display) { true }.applied)
        assertEquals(listOf(FullscreenDisplay.Size(1440, 3168), FullscreenDisplay.Size(3168, 1440)), display.commands)
    }
    @Test fun preserveOverrideAndRejectUnrecognizedWmOutput() {
        assertEquals(FullscreenDisplay.Size(1440, 3200), FullscreenDisplay.parseOverride("Physical size: 1440x3168\nOverride size: 1440x3200")?.override)
        assertNull(FullscreenDisplay.parseOverride("Physical size: 1440x3168")?.override)
        assertNull(FullscreenDisplay.parseOverride("Permission denied"))
    }
    @Test fun landscapeRequestOnPortraitPhoneUsesTheLongNaturalAxis() {
        val display = Display()
        assertTrue(FullscreenDisplay.apply(FullscreenDisplay.Size(3168, 1440), display) { true }.applied)
        assertEquals(FullscreenDisplay.Size(1440, 3168), display.overrideSize)
        assertEquals(1 to false, display.rotations.first())
    }
    @Test fun failedSwitchRestoresRotationAsWellAsSize() {
        val display = Display().apply { syncWorks = false }
        assertFalse(FullscreenDisplay.apply(FullscreenDisplay.Size(3168, 1440), display) { true }.applied)
        assertEquals(0 to false, display.rotations.last())
    }
    @Test fun portraitActivityCannotOverrideChosenLandscape() {
        val display = Display().apply { portraitActivity = true }
        assertTrue(FullscreenDisplay.apply(FullscreenDisplay.Size(3168, 1440), display) { true }.applied)
        assertTrue(display.fixedRotation)
    }
    @Test fun rollbackRestoresTheActivityOrientationPolicy() {
        val display = Display().apply { syncWorks = false }
        assertFalse(FullscreenDisplay.apply(FullscreenDisplay.Size(3168, 1440), display) { true }.applied)
        assertFalse(display.fixedRotation)
    }
    @Test fun idleRestoreRestoresSizeRotationAndPolicy() {
        val display = Display()
        val before = display.snapshot()
        assertTrue(FullscreenDisplay.apply(FullscreenDisplay.Size(1920, 1080), display) { true }.applied)
        assertTrue(FullscreenDisplay.restore(before, display))
        assertNull(display.overrideSize)
        assertEquals(0, display.angle)
        assertFalse(display.fixedRotation)
    }
    @Test fun readOnlyThePhysicalDisplayPolicyNotTheCaptureDisplay() {
        assertEquals(true, FullscreenDisplay.parseFixedRotation("Display: mDisplayId=22\nmFixedToUserRotation=false\nDisplay: mDisplayId=0 stacks=3\nmFixedToUserRotation=true"))
        assertEquals(false, FullscreenDisplay.parseFixedRotation("Display: mDisplayId=0 stacks=3\nmFixedToUserRotation=false\nDisplay: mDisplayId=22\nmFixedToUserRotation=true"))
        assertNull(FullscreenDisplay.parseFixedRotation("Permission denied"))
    }
}
