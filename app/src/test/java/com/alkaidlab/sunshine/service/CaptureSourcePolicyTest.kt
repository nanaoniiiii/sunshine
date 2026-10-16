package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class CaptureSourcePolicyTest {
    @Test fun tabletDesktopStaysFixedWhenAnotherClientReloadsAutoRotateSettings() {
        val beforeJoin = CaptureSourcePolicy.rotation(0, true, false, false)
        val afterJoin = CaptureSourcePolicy.rotation(0, true, true, false)
        assertEquals(beforeJoin, afterJoin)
        assertFalse(afterJoin.follow)
    }
    @Test fun desktopNeverInheritsPrimaryRotationCompatibility() {
        assertEquals(CaptureSourcePolicy.Rotation(false, false), CaptureSourcePolicy.rotation(0, true, true, true))
    }
    @Test fun secondaryNativeAndCompatibleDesktopsRemainFixed() {
        for (id in listOf(2, 17)) assertEquals(CaptureSourcePolicy.Rotation(false, false), CaptureSourcePolicy.rotation(id, true, true, true))
    }
    @Test fun exitingDesktopRestoresOrdinaryPrimaryFollowMode() {
        assertTrue(CaptureSourcePolicy.rotation(0, false, true, false).follow)
        assertFalse(CaptureSourcePolicy.rotation(0, false, true, false).compatibility)
    }
    @Test fun primaryScreenOffCaptureKeepsItsCompatibilityMode() {
        assertEquals(CaptureSourcePolicy.Rotation(false, true), CaptureSourcePolicy.rotation(0, false, true, true))
    }
}
