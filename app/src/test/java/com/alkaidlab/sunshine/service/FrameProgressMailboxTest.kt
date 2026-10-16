package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FrameProgressMailboxTest {
    @Test fun burstsKeepBothMilestonesWithOneWakeup() {
        val box = FrameProgressMailbox()
        var posts = 0
        repeat(1000) {
            if (box.offer(7, 123, false)) posts++
            if (box.offer(7, 123, true)) posts++
        }
        assertEquals(1, posts)
        val events = box.drain()
        assertEquals(2000, events.size)
        events.forEachIndexed { i, event -> assertEquals(i % 2 == 0, event.encoded); assertEquals(i % 2 == 1, event.released) }
        assertEquals(2000L, box.callbacks); assertEquals(1L, box.batches)
        assertTrue(box.offer(7, 456, false))
    }
    @Test fun clientIsolationAndReleaseBeforeEncodeRemainCorrect() {
        val box = FrameProgressMailbox()
        box.offer(1, 1000, false); box.offer(2, 1000, true); box.offer(1, 2000, true); box.offer(1, 2000, false)
        val first = FrameSubmissionBudget(); first.submitted(1_000_000); first.submitted(2_000_000)
        val second = FrameSubmissionBudget(); second.submitted(1_000_000)
        for (p in box.drain()) {
            val budget = if (p.key.threadId == 1L) first else second
            if (p.encoded) budget.encoded(p.key.ptsUs)
            if (p.released) budget.complete(p.key.ptsUs)
        }
        assertEquals(1, first.pendingCount); assertEquals(0, second.pendingCount)
        assertEquals(0L, first.encoderSkipped)
    }
    @Test fun concurrentProducersDoNotLoseCompletions() {
        val box = FrameProgressMailbox(); val pool = Executors.newFixedThreadPool(4); val done = CountDownLatch(4)
        try {
            repeat(4) { client -> pool.execute {
                repeat(1000) { pts -> box.offer(client.toLong(), pts.toLong(), false); box.offer(client.toLong(), pts.toLong(), true) }
                done.countDown()
            } }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            val events = box.drain()
            assertEquals(8000, events.size)
            val grouped = events.groupBy { it.key }
            assertEquals(4000, grouped.size)
            assertTrue(grouped.values.all { it.size == 2 && it[0].encoded && it[1].released })
            assertEquals(8000L, box.callbacks)
        } finally { pool.shutdownNow() }
    }
    @Test fun shutdownDiscardsOldEventsAndAllowsNewWakeup() {
        val box = FrameProgressMailbox(); box.offer(1, 1, true); box.clear(true)
        assertTrue(box.drain().isEmpty()); assertEquals(0L, box.callbacks)
        assertTrue(box.offer(2, 1, false)); assertEquals(2L, box.drain().single().key.threadId)
    }
    @Test fun lateCodecCallbackCannotMoveBeforeAnOlderEncodedPacket() {
        val box = FrameProgressMailbox(); val budget = FrameSubmissionBudget()
        for (n in 1..3) budget.submitted(n * 1_000_000L)
        box.offer(1, 3000, true) // Send ACK wins the race for frame 3.
        box.offer(1, 1000, false) // Frame 1 is encoded and still awaiting send.
        box.offer(1, 3000, false) // Codec callback for 3 arrives last.
        for (p in box.drain()) {
            if (p.encoded) budget.encoded(p.key.ptsUs)
            if (p.released) budget.complete(p.key.ptsUs)
        }
        assertEquals(1, budget.pendingCount)
        assertEquals(1L, budget.encoderSkipped) // Only frame 2 was skipped.
        assertTrue(budget.complete(1000))
    }
}
