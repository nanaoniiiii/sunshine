package com.alkaidlab.sunshine.controller

import org.junit.Assert.*
import org.junit.Test

class ControllerReducerTest {
    @Test fun buttonEdgesDoNotRepeatConfirm() {
        val reducer = ControllerReducer()
        assertEquals(listOf("A"), reducer.update(ControllerSample(buttons = 4096), 0))
        assertTrue(reducer.update(ControllerSample(buttons = 4096), 10).isEmpty())
        assertTrue(reducer.tick(1000).isEmpty())
        reducer.update(ControllerSample(), 1100)
        assertEquals(listOf("A"), reducer.update(ControllerSample(buttons = 4096), 1200))
    }
    @Test fun stickHasDeadZoneAndBoundedRepeat() {
        val reducer = ControllerReducer()
        reducer.update(ControllerSample(lx = .1f), 0)
        assertTrue(reducer.tick(0).isEmpty())
        reducer.update(ControllerSample(lx = .8f), 1)
        assertEquals(listOf("RIGHT"), reducer.tick(1))
        assertTrue(reducer.tick(300).isEmpty())
        assertEquals(listOf("RIGHT"), reducer.tick(381))
        reducer.update(ControllerSample(lx = .1f), 400)
        assertTrue(reducer.tick(600).isEmpty())
    }
    @Test fun nativeModePassesGameButtonsButKeepsSystemNavigation() {
        val reducer = ControllerReducer(); reducer.setMode(ControllerMode.NATIVE)
        assertTrue(reducer.update(ControllerSample(buttons = 4096), 0).isEmpty())
        assertEquals(4096, reducer.nativeSample().buttons)
        reducer.update(ControllerSample(), 1)
        assertEquals(listOf("SYS_HOME"), reducer.update(ControllerSample(buttons = 1024), 2))
        assertEquals(0, reducer.nativeSample().buttons)
        assertEquals(listOf("SYS_BACK"), reducer.update(ControllerSample(buttons = 16 or 8192), 3))
        assertEquals(16, reducer.nativeSample().buttons)
        assertEquals(listOf("SYS_RECENTS"), reducer.update(ControllerSample(buttons = 16 or 16384), 4))
        assertEquals(16, reducer.nativeSample().buttons)
    }
    @Test fun nativeSystemChordsWorkInEitherPressOrderWithoutRepeating() {
        for ((button, expected) in listOf(8192 to "SYS_BACK", 16384 to "SYS_RECENTS", 32768 to "SYS_NOTIFICATIONS")) {
            val faceFirst = ControllerReducer(); faceFirst.setMode(ControllerMode.NATIVE)
            assertTrue(faceFirst.update(ControllerSample(buttons = button), 0).isEmpty())
            assertEquals(button, faceFirst.nativeSample().buttons)
            assertEquals(listOf(expected), faceFirst.update(ControllerSample(buttons = button or 16), 10))
            assertEquals(16, faceFirst.nativeSample().buttons)
            assertTrue(faceFirst.update(ControllerSample(buttons = button or 16), 20).isEmpty())

            val startFirst = ControllerReducer(); startFirst.setMode(ControllerMode.NATIVE)
            assertTrue(startFirst.update(ControllerSample(buttons = 16), 0).isEmpty())
            assertEquals(listOf(expected), startFirst.update(ControllerSample(buttons = button or 16), 10))
            assertTrue(startFirst.update(ControllerSample(buttons = button or 16), 20).isEmpty())
        }
    }
    @Test fun aButtonHeldAcrossModeSwitchCannotBecomeASystemChord() {
        val reducer = ControllerReducer()
        reducer.update(ControllerSample(buttons = 8192), 0)
        reducer.setMode(ControllerMode.NATIVE)
        assertTrue(reducer.update(ControllerSample(buttons = 8192 or 16), 10).isEmpty())
        assertEquals(16, reducer.nativeSample().buttons)
    }
    @Test fun escapeWorksWithoutFurtherInputPacketsAndOnlyFiresOnce() {
        val reducer = ControllerReducer(); reducer.setMode(ControllerMode.NATIVE)
        reducer.update(ControllerSample(buttons = 48), 100)
        assertTrue(reducer.tick(999).isEmpty())
        assertEquals(listOf("ESCAPE"), reducer.tick(1000))
        reducer.setMode(ControllerMode.NAVIGATION)
        assertEquals(ControllerMode.NAVIGATION, reducer.mode)
        assertTrue(reducer.tick(2000).isEmpty())
    }
    @Test fun heldModeSwitchButtonIsNotForwardedIntoGame() {
        val reducer = ControllerReducer()
        reducer.update(ControllerSample(buttons = 128), 0); reducer.setMode(ControllerMode.NATIVE)
        assertEquals(0, reducer.nativeSample().buttons)
        reducer.update(ControllerSample(), 1)
        assertEquals(listOf("SYS_NAVIGATION"), reducer.update(ControllerSample(buttons = 128), 2))
        assertEquals(0, reducer.nativeSample().buttons)
    }
    @Test fun controllersDoNotShareModesOrEscapeTimers() {
        val first = ControllerReducer(); val second = ControllerReducer()
        first.setMode(ControllerMode.NATIVE)
        first.update(ControllerSample(buttons = 48), 0)
        assertEquals(listOf("A"), second.update(ControllerSample(buttons = 4096), 0))
        assertEquals(listOf("ESCAPE"), first.tick(900))
        assertEquals(ControllerMode.NAVIGATION, second.mode)
    }
}
