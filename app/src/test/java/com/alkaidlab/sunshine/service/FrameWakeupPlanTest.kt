package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class FrameWakeupPlanTest {
    @Test fun completionBurstRetainsAlreadyEarlierFrame() {
        val plan = FrameWakeupPlan()
        assertEquals(FrameWakeupPlan.Change.POST, plan.update(100))
        repeat(1000) { assertEquals(FrameWakeupPlan.Change.KEEP, plan.update(100 + it.toLong())) }
        assertEquals(1L, plan.posts); assertEquals(1000L, plan.retained)
    }
    @Test fun freshTexturePreemptsStaticKeepaliveAndBlockedBudget() {
        val plan = FrameWakeupPlan()
        assertEquals(FrameWakeupPlan.Change.POST, plan.update(250_000_000))
        assertEquals(FrameWakeupPlan.Change.POST, plan.update(8_000_000))
        assertEquals(FrameWakeupPlan.Change.KEEP, plan.update(350_000_000))
        plan.consumed()
        assertEquals(FrameWakeupPlan.Change.POST, plan.update(350_000_000))
    }
    @Test fun lastClientRemovalCancelsWakeupOnce() {
        val plan = FrameWakeupPlan(); plan.update(10)
        assertEquals(FrameWakeupPlan.Change.CANCEL, plan.update(null))
        assertEquals(FrameWakeupPlan.Change.KEEP, plan.update(null))
        assertEquals(FrameWakeupPlan.Change.POST, plan.update(20))
    }
    @Test fun resizeAndReconnectCannotRetainAnObsoleteDeadline() {
        val plan = FrameWakeupPlan(); plan.update(100); plan.consumed()
        assertEquals(FrameWakeupPlan.Change.POST, plan.update(200))
        plan.reset(); assertEquals(0L, plan.posts)
        assertEquals(FrameWakeupPlan.Change.POST, plan.update(300))
    }
}
