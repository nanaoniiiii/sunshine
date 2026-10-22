package com.alkaidlab.sunshine.controller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeyRouterTest {
    // Android meta-state values, without loading android.view.KeyEvent stubs.
    private val shift = 0x1
    private val ctrl = 0x1000
    private val alt = 0x2
    private val meta = 0x10000
    private val capsLock = 0x100000
    private val left = 21
    private val right = 22
    private fun router() = RemoteKeyRouter(shift or ctrl or alt or meta)

    @Test fun keyboardShortcutsPassThroughDownRepeatsAndUp() {
        for (modifier in listOf(shift, ctrl, alt, meta, shift or ctrl)) {
            val router = router()
            assertFalse(router.route(left, true, modifier, true))
            assertFalse(router.route(left, true, modifier, true))
            assertFalse(router.route(left, false, modifier, true))
        }
    }

    @Test fun releasingModifierBeforeArrowKeepsOriginalKeyboardRelease() {
        val router = router()
        assertFalse(router.route(left, true, shift, true))
        assertFalse(router.route(left, true, 0, true))
        assertFalse(router.route(left, false, 0, true))
        // A new unmodified press can be captured after the shortcut completes.
        assertTrue(router.route(left, true, 0, true))
        assertTrue(router.route(left, false, 0, true))
    }

    @Test fun capturedArrowKeepsRepeatsAndReleaseEvenIfModifierOrBindingChanges() {
        val router = router()
        assertTrue(router.route(right, true, 0, true))
        assertTrue(router.route(right, true, ctrl, false))
        assertTrue(router.route(right, false, ctrl, false))
        assertFalse(router.route(right, true, ctrl, true))
    }

    @Test fun keysRouteIndependentlyAndUnmatchedReleaseIsNotSwallowed() {
        val router = router()
        assertTrue(router.route(right, true, 0, true))
        assertFalse(router.route(left, true, shift, true))
        assertFalse(router.route(left, false, 0, true))
        assertTrue(router.route(right, false, shift, true))
        assertFalse(router.route(right, false, 0, true))
    }

    @Test fun unavailableAdapterDoesNotTakeOverHeldKeyAfterItStarts() {
        val router = router()
        assertFalse(router.route(left, true, 0, false))
        assertFalse(router.route(left, true, 0, true))
        assertFalse(router.route(left, false, 0, true))
        assertTrue(router.route(left, true, 0, true))
    }

    @Test fun lockStateDoesNotDisableUnmodifiedRemoteNavigation() {
        val router = router()
        assertTrue(router.route(left, true, capsLock, true))
        assertTrue(router.route(left, false, capsLock, true))
    }

    @Test fun disconnectClearsHeldRoutesAndCannotConsumeAnUnmatchedRelease() {
        val router = router()
        assertTrue(router.route(left, true, 0, true))
        router.clear()
        assertFalse(router.route(left, false, 0, true))
        assertFalse(router.route(left, true, ctrl, true))
    }
}
