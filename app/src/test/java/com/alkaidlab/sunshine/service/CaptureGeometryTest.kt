package com.alkaidlab.sunshine.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureGeometryTest {
    @Test fun nativeLandscapeUsesEveryEncoderPixel() {
        assertEquals(CaptureGeometry.Viewport(0, 0, 3168, 1440), CaptureGeometry.fit(3168, 1440, 3168, 1440))
    }
    @Test fun nativePortraitUsesEveryEncoderPixel() {
        assertEquals(CaptureGeometry.Viewport(0, 0, 1440, 3168), CaptureGeometry.fit(1440, 3168, 1440, 3168))
    }
    @Test fun lowResolutionClientDoesNotDetermineTheSourceAspect() {
        assertEquals(CaptureGeometry.Viewport(0, 104, 1920, 872), CaptureGeometry.fit(3168, 1440, 1920, 1080))
        assertEquals(CaptureGeometry.Viewport(0, 69, 1280, 581), CaptureGeometry.fit(3168, 1440, 1280, 720))
    }
    @Test fun differentOrientationsKeepOneCorrectFit() {
        assertEquals(CaptureGeometry.Viewport(1257, 0, 654, 1440), CaptureGeometry.fit(1440, 3168, 3168, 1440))
    }
    @Test fun similarDeviceAspectOnlyNeedsSmallBars() {
        assertEquals(CaptureGeometry.Viewport(16, 0, 3168, 1440), CaptureGeometry.fit(3168, 1440, 3200, 1440))
    }
}
