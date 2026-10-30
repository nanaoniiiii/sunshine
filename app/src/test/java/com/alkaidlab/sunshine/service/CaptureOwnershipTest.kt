package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class CaptureOwnershipTest {
    @Test fun recognizesRealIdleDump() {
        assertSame(CaptureOwnership.None, CaptureOwnership.parse("MEDIA PROJECTION MANAGER (dumpsys media_projection)\nMedia Projection: \nnull\n"))
    }
    @Test fun extractsForeignOwnerWithoutTreatingItAsIdle() {
        assertEquals(CaptureOwnership.Active("com.samsung.android.galaxycontinuity"), CaptureOwnership.parse(
            "MEDIA PROJECTION MANAGER\nMedia Projection: \n(com.samsung.android.galaxycontinuity, uid=10403): TYPE_SCREEN_CAPTURE\n"))
    }
    @Test fun recognizesSelfProjectionSeparatelyFromNoCapture() {
        assertEquals(CaptureOwnership.Active("com.alkaidlab.sunshine"), CaptureOwnership.parse(
            "Media Projection: \n(com.alkaidlab.sunshine, uid=10582): TYPE_SCREEN_CAPTURE"))
    }
    @Test fun missingPrivilegeAndTruncatedDumpsAreUnknown() {
        for (dump in listOf(null, "", "Permission Denial: dumpsys media_projection", "null", "Media Projection:",
            "Media Projection: (com.other, uid=", "Media Projection: null\nPermission Denial")) {
            assertSame("Must not take over on $dump", CaptureOwnership.Unknown, CaptureOwnership.parse(dump))
        }
    }
}
