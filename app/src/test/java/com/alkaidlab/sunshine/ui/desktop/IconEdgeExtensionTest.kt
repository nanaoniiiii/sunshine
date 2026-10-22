package com.alkaidlab.sunshine.ui.desktop

import org.junit.Assert.*
import org.junit.Test

class IconEdgeExtensionTest {
    @Test fun everySideRetainsItsLocalEdgeColour() {
        val source = IntArray(25 * 25) { index ->
            val x = index % 25
            val y = index / 25
            when {
                x < 5 -> 0xffff0000.toInt()
                x >= 20 -> 0xff0000ff.toInt()
                y < 5 -> 0xff00ff00.toInt()
                y >= 20 -> 0xffffff00.toInt()
                else -> 0xff000000.toInt()
            }
        }
        val result = IconEdgeExtension.create(source, 25, 25, 12)
        fun colour(x: Int, y: Int) = result.pixels[y * result.width + x] and 0x00ffffff
        assertEquals(0x00ff0000, colour(11, 24))
        assertEquals(0x000000ff, colour(37, 24))
        assertEquals(0x0000ff00, colour(24, 11))
        assertEquals(0x00ffff00, colour(24, 37))
    }

    @Test fun extensionFadesOutInAllDirectionsAndDoesNotModifySource() {
        val source = IntArray(9) { 0xff123456.toInt() }
        val original = source.copyOf()
        val result = IconEdgeExtension.create(source, 3, 3, 4)
        fun alpha(x: Int, y: Int) = result.pixels[y * result.width + x] ushr 24
        assertTrue(alpha(3, 5) > alpha(1, 5))
        assertTrue(alpha(7, 5) > alpha(9, 5))
        assertTrue(alpha(5, 3) > alpha(5, 1))
        assertTrue(alpha(5, 7) > alpha(5, 9))
        for (i in 0 until result.width) {
            assertEquals(0, alpha(i, 0))
            assertEquals(0, alpha(i, result.height - 1))
        }
        assertArrayEquals(original, source)
    }

    @Test fun transparentCornersExtendFromVisibleContourInsteadOfTransparentBlack() {
        val source = IntArray(9)
        source[4] = 0xff42a0e0.toInt()
        val result = IconEdgeExtension.create(source, 3, 3, 4)
        val corner = result.pixels[4 * result.width + 4]
        assertEquals(0x0042a0e0, corner and 0x00ffffff)
        assertTrue(corner ushr 24 > 0)
        assertTrue(IconEdgeExtension.create(IntArray(9), 3, 3, 4).pixels.all { it == 0 })
    }

    @Test fun differentEdgeSitesBlendWithoutAHardColourLineOutsideIcon() {
        val source = IntArray(32 * 32)
        source[16 * 32 + 8] = 0xffff0000.toInt()
        source[16 * 32 + 24] = 0xff0000ff.toInt()
        val result = IconEdgeExtension.create(source, 32, 32, 32)
        fun red(x: Int) = result.pixels[64 * result.width + x] ushr 16 and 255
        val centreRed = red(48)
        assertTrue("both edge colours should contribute: $centreRed", centreRed in 80..175)
        for (x in 44..52) assertTrue("no nearest-site colour jump", kotlin.math.abs(red(x + 1) - red(x)) < 32)
    }
}
