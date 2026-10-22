package com.alkaidlab.sunshine.controller

import org.junit.Assert.*
import org.junit.Test

class RadialGestureTest {
    @Test fun triggerPressOpensImmediatelyAndCenteredReleaseCancels() {
        val ring = RadialGesture()
        assertEquals(listOf(RadialEvent.Open(RadialKind.ACTIONS)), ring.update(ControllerSample(lt = 1f), 0, true))
        assertEquals(listOf(RadialEvent.Release(-1)), ring.update(ControllerSample(), 200, true))
    }
    @Test fun holdOpensAndReleaseCommitsSelectedSector() {
        val ring = RadialGesture()
        ring.update(ControllerSample(lt = 1f), 0, true)
        assertEquals(listOf(RadialEvent.Select(2)), ring.update(ControllerSample(lt = 1f, lx = 1f), 20, true))
        assertEquals(listOf(RadialEvent.Release(2)), ring.update(ControllerSample(lx = 1f), 500, true))
        assertTrue(ring.update(ControllerSample(lx = 1f), 510, true).isEmpty())
    }
    @Test fun rightTriggerUsesAppsAndEitherStickCanSelect() {
        val ring = RadialGesture()
        ring.update(ControllerSample(rt = 1f), 0, true)
        assertEquals(listOf(RadialEvent.Select(6)), ring.update(ControllerSample(rt = 1f, rx = -1f), 1, true))
        assertEquals(listOf(RadialEvent.Select(0)), ring.update(ControllerSample(rt = 1f, ly = -1f), 452, true))
    }
    @Test fun centerIsCancelAndModeChangeCannotAccidentallyExecute() {
        val ring = RadialGesture()
        ring.update(ControllerSample(lt = 1f), 0, true)
        assertTrue(ring.update(ControllerSample(lt = 1f), 1, true).isEmpty())
        assertEquals(listOf(RadialEvent.Release(-1)), ring.update(ControllerSample(), 451, true))
        ring.update(ControllerSample(), 452, true)
        ring.update(ControllerSample(rt = 1f), 500, true)
        assertTrue(ring.update(ControllerSample(rt = 1f, rx = 1f), 1000, false).isEmpty())
        assertTrue(ring.update(ControllerSample(), 1001, true).isEmpty())
    }
    @Test fun wheelCannotReopenUntilBothTriggersReleased() {
        val ring = RadialGesture()
        ring.update(ControllerSample(lt = 1f), 0, true)
        ring.update(ControllerSample(lt = 1f, rt = 1f), 20, true)
        ring.update(ControllerSample(rt = 1f), 21, true)
        assertTrue(ring.update(ControllerSample(rt = 1f), 2000, true).isEmpty())
    }
    @Test fun angularSectorsAndDeadzoneAreStable() {
        assertEquals(-1, RadialGesture.index(.1f, -.1f, 8))
        assertEquals(0, RadialGesture.index(0f, -1f, 8))
        assertEquals(2, RadialGesture.index(1f, 0f, 8))
        assertEquals(4, RadialGesture.index(0f, 1f, 8))
        assertEquals(6, RadialGesture.index(-1f, 0f, 8))
        assertEquals(-1, RadialGesture.index(1f, 0f, 0))
    }
    @Test fun aSingleNeutralReleaseAllowsNextTriggerImmediately() {
        val ring = RadialGesture()
        ring.update(ControllerSample(lt = 1f), 0, true)
        ring.update(ControllerSample(lt = 1f), 0, true)
        ring.update(ControllerSample(), 451, true)
        assertEquals(listOf(RadialEvent.Open(RadialKind.APPS)), ring.update(ControllerSample(rt = 1f), 452, true))
    }
}
