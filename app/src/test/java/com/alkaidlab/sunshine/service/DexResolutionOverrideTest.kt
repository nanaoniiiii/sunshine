package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class DexResolutionOverrideTest {
    private class Device : DexResolutionOverride.Backend {
        var original: FullscreenDisplay.Size? = null
        var rotationValue = 0
        var alive = true
        var accept = true
        var failAfterWrite = false
        var checks = 0
        val writes = mutableListOf<FullscreenDisplay.Size?>()
        override fun snapshot() = FullscreenDisplay.Snapshot(original, 0)
        override fun rotation() = rotationValue
        override fun sameDisplay() = alive
        override fun setSize(size: FullscreenDisplay.Size?) {
            writes.add(size)
            if (failAfterWrite) { failAfterWrite = false; error("OEM changed size before error") }
        }
        override fun awaitSize(size: FullscreenDisplay.Size): Boolean { checks++; return accept }
    }
    @Test fun externalDesktopGetsExactPortraitRequestAndRestoresNoOverride() {
        val device = Device()
        val change = DexResolutionOverride(2, device)
        change.apply(1440, 3168)
        assertEquals(FullscreenDisplay.Size(1440, 3168), device.writes.single())
        change.close()
        assertNull(device.writes.last())
    }
    @Test fun existingUserOverrideIsRestored() {
        val device = Device().apply { original = FullscreenDisplay.Size(1600, 900) }
        val change = DexResolutionOverride(2, device)
        change.apply(2560, 1440)
        change.close()
        assertEquals(device.original, device.writes.last())
    }
    @Test fun rotatedDisplayUsesNaturalSizeForWm() {
        val device = Device().apply { rotationValue = 1 }
        DexResolutionOverride(2, device).apply(1440, 3168)
        assertEquals(FullscreenDisplay.Size(3168, 1440), device.writes.single())
    }
    @Test fun ignoredSizeCannotBeReportedAsApplied() {
        val device = Device().apply { accept = false }
        assertThrows(IllegalStateException::class.java) { DexResolutionOverride(2, device).apply(2560, 1440) }
        assertEquals(2, device.checks)
        assertNull(device.writes.last())
    }
    @Test fun partiallyAppliedCommandIsRolledBack() {
        val device = Device().apply { failAfterWrite = true }
        assertThrows(IllegalStateException::class.java) { DexResolutionOverride(2, device).apply(2560, 1440) }
        assertEquals(2, device.writes.size)
        assertNull(device.writes.last())
    }
    @Test fun disconnectedOrReplacedDisplayIsNotRestoredByIdAlone() {
        val device = Device()
        val change = DexResolutionOverride(2, device)
        change.apply(2560, 1440)
        device.alive = false
        change.close()
        assertEquals(1, device.writes.size)
    }
    @Test fun tabletPrimaryDisplayCannotBeOverridden() {
        assertThrows(IllegalArgumentException::class.java) { DexResolutionOverride(0, Device()) }
    }
}
