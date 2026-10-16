package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class FramePacerTest {
    @org.junit.Test fun virtualFramesWaitForFreshBuffersButKeepStaticStreamsAlive() {
        val previous = 1_000_000_000L
        val deadline = previous + 16_666_666L
        org.junit.Assert.assertEquals(previous + 250_000_000L,
            FramePacer.nextSubmissionNs(deadline, previous, true, true, true))
        org.junit.Assert.assertEquals(deadline,
            FramePacer.nextSubmissionNs(deadline, previous, false, true, true))
        org.junit.Assert.assertEquals(deadline,
            FramePacer.nextSubmissionNs(deadline, previous, true, true, false))
        org.junit.Assert.assertNull(FramePacer.nextSubmissionNs(deadline, previous, true, false, true))
        org.junit.Assert.assertEquals(previous + 500_000_000L,
            FramePacer.nextSubmissionNs(previous + 500_000_000L, previous, true, true, true))
    }
    @org.junit.Test fun virtualSourcesUseActualRefreshWithoutLimitingPrimaryOrRealHighRefresh() {
        org.junit.Assert.assertEquals(60, FramePacer.effectiveFps(120, 240, true, 60f))
        org.junit.Assert.assertEquals(60, FramePacer.effectiveFps(120, 240, true, 59.94f))
        org.junit.Assert.assertEquals(120, FramePacer.effectiveFps(120, 240, true, 120f))
        org.junit.Assert.assertEquals(30, FramePacer.effectiveFps(30, 240, true, 60f))
        org.junit.Assert.assertEquals(45, FramePacer.effectiveFps(120, 45, true, 60f))
        org.junit.Assert.assertEquals(120, FramePacer.effectiveFps(120, 240, false, 60f))
        org.junit.Assert.assertEquals(60, FramePacer.effectiveFps(120, 240, true, Float.NaN))
        org.junit.Assert.assertEquals(60, FramePacer.effectiveFps(120, 240, true, 0f))
    }

    @org.junit.Test fun changingToVirtualSourceStopsDoubleRateRepeatsAndRestoresPrimaryRate() {
        val pacer = FramePacer(120)
        pacer.setFps(FramePacer.effectiveFps(120, 240, true, 60f))
        var submissions = 0
        for (ms in 0 until 1000) if (pacer.isDue(ms * 1_000_000L)) {
            submissions++; pacer.advance(ms * 1_000_000L)
        }
        org.junit.Assert.assertEquals(60, submissions)
        pacer.setFps(FramePacer.effectiveFps(120, 240, false, 60f))
        org.junit.Assert.assertEquals(120, pacer.fps)
        org.junit.Assert.assertTrue(pacer.isDue(1_000_000_000L))
    }
    @Test fun mixedClientsKeepTheirRatesWhenCaptureIsFaster() {
        val clients = listOf(FramePacer(30), FramePacer(60), FramePacer(120))
        val submitted = IntArray(clients.size)
        // Simulate a 1ms handler clock for ten seconds, irrespective of capture callback rate.
        for (ms in 0L until 10_000L) {
            val now = 1_000_000_000L + ms * 1_000_000L
            clients.forEachIndexed { index, pacer ->
                if (pacer.isDue(now)) { submitted[index]++; pacer.advance(now) }
            }
        }
        assertArrayEquals(intArrayOf(300, 600, 1200), submitted)
    }

    @Test fun aLongStallDoesNotCauseACatchUpBurst() {
        val pacer = FramePacer(60)
        pacer.advance(1_000_000_000)
        val resumed = 2_000_000_000L
        assertTrue(pacer.isDue(resumed))
        pacer.advance(resumed)
        assertFalse(pacer.isDue(resumed))
        assertTrue(pacer.nextFrameNs > resumed)
        assertTrue(pacer.nextFrameNs - resumed <= 1_000_000_000L / 60)
    }

    @Test fun changingTheCapDoesNotRetainAnOldDeadline() {
        val pacer = FramePacer(120)
        pacer.advance(1_000_000_000)
        pacer.setFps(30)
        assertTrue(pacer.isDue(1_000_000_001))
        pacer.advance(1_000_000_001)
        assertEquals(33_333_333L, pacer.nextFrameNs - 1_000_000_001)
    }

    @Test fun earlyCaptureNotificationsCannotOverfeedAnEncoder() {
        val pacer = FramePacer(30)
        pacer.advance(1_000_000_000)
        for (offset in 1L..33L) assertFalse(pacer.isDue(1_000_000_000 + offset * 1_000_000))
        assertTrue(pacer.isDue(1_034_000_000))
    }
}
