package com.alkaidlab.sunshine.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureGeometryTest {
    @Test fun compatibilityKeepsTheNativePortraitCanvasFull() {
        assertEquals(1, CaptureGeometry.compatibilityTurn(3168, 1440, 1440, 3168, 1, true))
        assertEquals(3, CaptureGeometry.compatibilityTurn(3168, 1440, 1440, 3168, 3, true))
        assertEquals(0, CaptureGeometry.compatibilityTurn(1440, 3168, 1440, 3168, 0, true))
        assertEquals(0, CaptureGeometry.compatibilityTurn(3168, 1440, 1440, 3168, 1, false))
        assertEquals(CaptureGeometry.Viewport(0, 0, 1440, 3168), CaptureGeometry.orientedFit(3168, 1440, 1440, 3168, 1))
    }
    @Test fun bothLandscapeDirectionsInvertTouchRotation() {
        assertEquals(1584f to 720f, CaptureGeometry.mapPosition(720f, 1584f, 3168, 1440, 1440, 3168, 1))
        assertEquals(0f to 1439f, CaptureGeometry.mapPosition(0f, 0f, 3168, 1440, 1440, 3168, 1))
        assertEquals(3167f to 0f, CaptureGeometry.mapPosition(1440f, 3168f, 3168, 1440, 1440, 3168, 1))
        assertEquals(3167f to 0f, CaptureGeometry.mapPosition(0f, 0f, 3168, 1440, 1440, 3168, 3))
        assertEquals(0f to 1439f, CaptureGeometry.mapPosition(1440f, 3168f, 3168, 1440, 1440, 3168, 3))
    }
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
    @Test fun touchCoordinatesFollowPortraitAndLandscapeCanvases() {
        assertEquals(720f to 1600f, CaptureGeometry.mapPosition(360f, 640f, 1440, 3200, 720, 1280))
        assertEquals(1600f to 720f, CaptureGeometry.mapPosition(640f, 360f, 3200, 1440, 1280, 720))
        assertEquals(0f to 0f, CaptureGeometry.mapPosition(0f, 0f, 3200, 1440, 1280, 720))
        assertEquals(3199f to 1439f, CaptureGeometry.mapPosition(1280f, 720f, 3200, 1440, 1280, 720))
        assertEquals(1439f to 3199f, CaptureGeometry.mapPosition(720f, 1280f, 1440, 3200, 720, 1280))
    }
}
