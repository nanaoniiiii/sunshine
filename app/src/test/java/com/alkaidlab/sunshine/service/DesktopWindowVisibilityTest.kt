package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class DesktopWindowVisibilityTest {
    private fun window(display: Int = 9, visible: Boolean = true, drawn: String = "HAS_DRAWN", owner: Int = 2000, name: String = "Sunshine 桌面控制") = """
  Window #1 Window{abc u0 $name}:
    mDisplayId=$display mOwnerUid=$owner
    mHasSurface=true mAppOpVisibility=$visible
    mDrawState=$drawn isOnScreen=$visible
"""
    @Test fun shownOwnedWindowOnTargetDisplayPasses() = assertTrue(DesktopWindowVisibility.visible(window(), 9))
    @Test fun surfaceAddedButHiddenByAppOpsFails() = assertFalse(DesktopWindowVisibility.visible(window(visible = false), 9))
    @Test fun primaryScreenCannotSatisfySecondaryScreenCheck() = assertFalse(DesktopWindowVisibility.visible(window(display = 0) + "\nDisplay: mDisplayId=9", 9))
    @Test fun notYetDrawnDoesNotCountAsVisible() = assertFalse(DesktopWindowVisibility.visible(window(drawn = "READY_TO_SHOW"), 9))
    @Test fun visibleOtherWindowCannotMaskHiddenControls() = assertFalse(DesktopWindowVisibility.visible(window(visible = false) + window(name = "Other"), 9))
    @Test fun anotherApplicationCannotSatisfyOwnedWindowCheck() = assertFalse(DesktopWindowVisibility.visible(window(owner = 10580), 9))
    @Test fun multipleDisplaysAreCheckedIndependently() {
        val dump = window(display = 0) + window(display = 9, visible = false)
        assertTrue(DesktopWindowVisibility.visible(dump, 0))
        assertFalse(DesktopWindowVisibility.visible(dump, 9))
    }
    @Test fun normalPolicyFieldsMayBeOmittedByDumpsys() {
        assertTrue(DesktopWindowVisibility.visible(window().replace("mAppOpVisibility=true", ""), 9))
    }
}
