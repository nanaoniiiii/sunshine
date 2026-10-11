package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class FramePacerTest {
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
