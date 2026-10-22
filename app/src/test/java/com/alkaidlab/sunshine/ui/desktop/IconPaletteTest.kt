package com.alkaidlab.sunshine.ui.desktop
import org.junit.Assert.*
import org.junit.Test

class IconPaletteTest {
    @Test fun choosesMinorityArtColorOverDominantWhiteBorder() {
        val pixels = IntArray(1000) { if (it < 750) 0xffffffff.toInt() else 0xffd02020.toInt() }
        val result = IconPalette.extract(pixels)
        assertTrue(result.matched); assertEquals(.25f, result.fraction, .001f)
        assertTrue((result.color ushr 16 and 255) > (result.color and 255) * 3)
    }
    @Test fun strictTenAndFiftyPercentBoundariesAreExcluded() {
        val pixels = IntArray(1000) { when { it < 500 -> 0xffffffff.toInt(); it < 900 -> 0xff0080e0.toInt(); else -> 0xffe02020.toInt() } }
        val result = IconPalette.extract(pixels)
        assertTrue(result.matched); assertEquals(.4f, result.fraction, .001f)
    }
    @Test fun singleColorAndTransparentIconsHaveDefinedFallback() {
        assertFalse(IconPalette.extract(IntArray(100) { 0xff204060.toInt() }).matched)
        assertFalse(IconPalette.extract(IntArray(100)).matched)
        assertFalse(IconPalette.extract(intArrayOf()).matched)
    }
}
