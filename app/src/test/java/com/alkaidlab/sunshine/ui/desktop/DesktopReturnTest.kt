package com.alkaidlab.sunshine.ui.desktop

import org.junit.Assert.*
import org.junit.Test

class DesktopReturnTest {
    @Test fun outerRimWinsEvenWhenInteriorHasAnotherDominantColour() {
        val red = 0xffff0000.toInt(); val blue = 0xff0000ff.toInt()
        val pixels = IntArray(20 * 20) { blue }
        for (i in 0..19) { pixels[i] = red; pixels[19 * 20 + i] = red; pixels[i * 20] = red; pixels[i * 20 + 19] = red }
        val result = IconBorderColor.extract(pixels, 20, 20)
        assertEquals(red, result.color)
        assertEquals(1f, result.fraction)
    }
    @Test fun transparentPaddingAndInternalHoleDoNotVote() {
        val green = 0xff00aa00.toInt(); val pixels = IntArray(12 * 12)
        for (y in 2..9) for (x in 2..9) pixels[y * 12 + x] = green
        for (y in 4..7) for (x in 4..7) pixels[y * 12 + x] = 0
        assertEquals(green, IconBorderColor.extract(pixels, 12, 12).color)
        assertFalse(IconBorderColor.extract(IntArray(4), 2, 2).matched)
    }
    @Test fun mostFrequentRimColourIsUsedWithoutBlendingOtherColours() {
        val black = 0xff000000.toInt(); val red = 0xffff0000.toInt()
        val p = IntArray(100) { black }
        for (x in 0..9) p[x] = red
        assertEquals(black, IconBorderColor.extract(p, 10, 10).color)
    }
    @Test fun returnedDesktopRetainsZoneAndScrollIndependentlyPerDisplay() {
        val selected = DesktopPosition("quick", selected = 27, selectedKey = "example/.Main", zone = "quick",
            quickSelected = 2, railIndex = 23, railOffset = 17)
        DesktopSessionCache.save(21, selected)
        DesktopSessionCache.save(22, DesktopPosition(selected = 3))
        assertEquals(selected, DesktopSessionCache.read(21))
        assertEquals(3, DesktopSessionCache.read(22).selected)
        DesktopSessionCache.clear(21)
        assertEquals(DesktopPosition(), DesktopSessionCache.read(21))
        DesktopSessionCache.clear(22)
    }
}
