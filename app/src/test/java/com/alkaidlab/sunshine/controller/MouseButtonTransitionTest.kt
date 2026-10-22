package com.alkaidlab.sunshine.controller

import org.junit.Assert.assertEquals
import org.junit.Test

class MouseButtonTransitionTest {
    @Test fun primaryClickMatchesMouseSequence() {
        assertEquals(listOf(MouseStep(MouseStepAction.DOWN, 1), MouseStep(MouseStepAction.BUTTON_PRESS, 1, 1)), MouseButtonTransition.steps(0, 1))
        assertEquals(listOf(MouseStep(MouseStepAction.BUTTON_RELEASE, 0, 1), MouseStep(MouseStepAction.UP, 0),
            MouseStep(MouseStepAction.HOVER_MOVE, 0)), MouseButtonTransition.steps(1, 0))
    }

    @Test fun secondaryClickIdentifiesChangedButton() {
        assertEquals(listOf(MouseStep(MouseStepAction.DOWN, 2), MouseStep(MouseStepAction.BUTTON_PRESS, 2, 2)), MouseButtonTransition.steps(0, 2))
        assertEquals(listOf(MouseStep(MouseStepAction.BUTTON_RELEASE, 0, 2), MouseStep(MouseStepAction.UP, 0),
            MouseStep(MouseStepAction.HOVER_MOVE, 0)), MouseButtonTransition.steps(2, 0))
    }

    @Test fun twoButtonsKeepPointerDownUntilBothAreReleased() {
        assertEquals(listOf(MouseStep(MouseStepAction.MOVE, 3), MouseStep(MouseStepAction.BUTTON_PRESS, 3, 2)), MouseButtonTransition.steps(1, 3))
        assertEquals(listOf(MouseStep(MouseStepAction.BUTTON_RELEASE, 1, 2), MouseStep(MouseStepAction.MOVE, 1)), MouseButtonTransition.steps(3, 1))
        assertEquals(listOf(MouseStep(MouseStepAction.BUTTON_RELEASE, 0, 1), MouseStep(MouseStepAction.UP, 0),
            MouseStep(MouseStepAction.HOVER_MOVE, 0)), MouseButtonTransition.steps(1, 0))
    }

    @Test fun simultaneousChordEmitsSeparateButtonChanges() {
        assertEquals(listOf(MouseStep(MouseStepAction.DOWN, 3), MouseStep(MouseStepAction.BUTTON_PRESS, 1, 1),
            MouseStep(MouseStepAction.BUTTON_PRESS, 3, 2)), MouseButtonTransition.steps(0, 3))
        assertEquals(listOf(MouseStep(MouseStepAction.BUTTON_RELEASE, 2, 1), MouseStep(MouseStepAction.BUTTON_RELEASE, 0, 2),
            MouseStep(MouseStepAction.UP, 0), MouseStep(MouseStepAction.HOVER_MOVE, 0)), MouseButtonTransition.steps(3, 0))
    }
}
