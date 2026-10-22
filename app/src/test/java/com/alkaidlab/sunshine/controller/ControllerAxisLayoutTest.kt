package com.alkaidlab.sunshine.controller

import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerAxisLayoutTest {
    @Test fun samsungXboxSeparatesBrakeGasFromRightStick() {
        assertEquals(ControllerAxisLayout(2, 5, 10, 9),
            ControllerAxisLayout.detect(setOf(0, 1, 2, 5, 9, 10, 16, 17)))
    }

    @Test fun commonRxRyLayoutUsesZAndRzTriggers() {
        assertEquals(ControllerAxisLayout(3, 4, 2, 5),
            ControllerAxisLayout.detect(setOf(0, 1, 2, 3, 4, 5, 16, 17)))
    }

    @Test fun leftStickOnlyDoesNotBecomeRightStick() {
        assertEquals(ControllerAxisLayout(-1, -1, -1, -1), ControllerAxisLayout.detect(setOf(0, 1)))
    }

    @Test fun digitalRemoteHasNoStickOrTriggerAxes() {
        assertEquals(ControllerAxisLayout(-1, -1, -1, -1), ControllerAxisLayout.detect(setOf(16, 17)))
    }
}
