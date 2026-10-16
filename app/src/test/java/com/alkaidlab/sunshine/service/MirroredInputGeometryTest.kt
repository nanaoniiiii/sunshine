package com.alkaidlab.sunshine.service

import org.junit.Assert.assertEquals
import org.junit.Test

class MirroredInputGeometryTest {
    private fun assertPoint(x: Float, y: Float, point: Pair<Float, Float>) {
        assertEquals(x, point.first, 0.02f)
        assertEquals(y, point.second, 0.02f)
    }

    // Project a known DeX point through both rendering stages, including integer viewport rounding.
    private fun project(x: Float, y: Float, targetWidth: Int, targetHeight: Int): Pair<Float, Float> {
        val mirror = CaptureGeometry.fit(1920, 1080, 1440, 3168)
        val encoder = CaptureGeometry.fit(1440, 3168, targetWidth, targetHeight)
        return (encoder.x + (mirror.x + x / 1920 * mirror.width) / 1440 * encoder.width) to
            (encoder.y + (mirror.y + y / 1080 * mirror.height) / 3168 * encoder.height)
    }

    @Test fun dexGridRoundTripsThroughPortraitMirrorAndDifferentEncoderCanvases() {
        for ((width, height) in listOf(1440 to 3168, 1920 to 1080, 1280 to 720, 720 to 1280)) {
            for (x in listOf(1f, 480f, 960f, 1440f, 1918f)) {
                for (y in listOf(1f, 270f, 540f, 810f, 1078f)) {
                    val p = project(x, y, width, height)
                    assertPoint(x, y, CaptureGeometry.mapCapturedPosition(p.first, p.second,
                        1920, 1080, 1440, 3168, width, height))
                }
            }
        }
    }

    @Test fun mirroredSwipeKeepsItsDirectionAndDistance() {
        for (y in listOf(100f, 200f, 400f, 800f)) {
            val p = project(600f, y, 1920, 1080)
            assertPoint(600f, y, CaptureGeometry.mapCapturedPosition(p.first, p.second,
                1920, 1080, 1440, 3168, 1920, 1080))
        }
    }

    @Test fun nativeDexCaptureFitsEachClientOnceAndPreservesTouchCoordinates() {
        val landscape = CaptureGeometry.fit(1920, 1080, 2560, 1440)
        assertEquals(0, landscape.x); assertEquals(0, landscape.y)
        assertEquals(2560, landscape.width); assertEquals(1440, landscape.height)
        for ((width, height) in listOf(1440 to 3168, 2560 to 1440)) {
            val viewport = CaptureGeometry.fit(1920, 1080, width, height)
            for (x in listOf(1f, 960f, 1918f)) for (y in listOf(1f, 540f, 1078f)) {
                val encodedX = viewport.x + x / 1920 * viewport.width
                val encodedY = viewport.y + y / 1080 * viewport.height
                assertPoint(x, y, CaptureGeometry.mapCapturedPosition(encodedX, encodedY,
                    1920, 1080, 1920, 1080, width, height))
            }
        }
    }

    @Test fun pointsOutsideTheMirrorClampToDisplayBounds() {
        assertPoint(0f, 0f, CaptureGeometry.mapCapturedPosition(-10f, -10f,
            1920, 1080, 1440, 3168, 1440, 3168))
        assertPoint(1919f, 1079f, CaptureGeometry.mapCapturedPosition(1450f, 3200f,
            1920, 1080, 1440, 3168, 1440, 3168))
    }

    @Test fun ordinaryDisplayPreservesBothRotationCompatibilityDirections() {
        for (turn in listOf(0, 1, 3)) {
            for ((x, y) in listOf(0f to 0f, 720f to 1584f, 1440f to 3168f)) {
                assertEquals(CaptureGeometry.mapPosition(x, y, 3168, 1440, 1440, 3168, turn),
                    CaptureGeometry.mapCapturedPosition(x, y, 3168, 1440, 3168, 1440, 1440, 3168, turn))
            }
        }
    }
}
