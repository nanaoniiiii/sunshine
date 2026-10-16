package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class SourceCadenceTrackerTest {
    private fun feed(tracker: SourceCadenceTracker, fps: Int, seconds: Int, start: Long = 1_000_000_000L): Long {
        var last = start
        for (index in 0..fps * seconds) {
            last = start + index * 1_000_000_000L / fps
            tracker.observe(last)
        }
        return last
    }

    @Test fun twoStableWindowsDemonstrateInheritedHighRefresh() {
        val tracker = SourceCadenceTracker()
        feed(tracker, 120, 1)
        assertEquals(0, tracker.learnedFps)
        feed(tracker, 120, 2, 2_000_000_000L)
        assertEquals(120, tracker.learnedFps)
    }

    @Test fun duplicateCallbacksCannotTurnSixtyFramesIntoOneHundredTwenty() {
        val tracker = SourceCadenceTracker()
        for (index in 0..180) {
            val timestamp = 1_000_000_000L + index * 1_000_000_000L / 60
            repeat(4) { tracker.observe(timestamp) }
        }
        assertEquals(60, tracker.learnedFps)
    }

    @Test fun anIsolatedFastWindowDoesNotRaiseTheCeiling() {
        val tracker = SourceCadenceTracker()
        val last = feed(tracker, 120, 1)
        feed(tracker, 60, 2, last)
        assertEquals(60, tracker.learnedFps)
    }

    @Test fun staticScenesKeepTheCeilingUntilTheSourceChanges() {
        val tracker = SourceCadenceTracker()
        var last = feed(tracker, 120, 3)
        repeat(20) { last += 300_000_000L; tracker.observe(last) }
        assertEquals(120, tracker.learnedFps)
        tracker.reset()
        feed(tracker, 60, 3)
        assertEquals(60, tracker.learnedFps)
    }

    @Test fun invalidOrRebasedTimestampsCannotCreateAFrameBurst() {
        val tracker = SourceCadenceTracker()
        tracker.observe(0); tracker.observe(-1)
        feed(tracker, 120, 1)
        tracker.observe(1_000_000L)
        assertEquals(0, tracker.learnedFps)
        feed(tracker, 60, 3, 1_000_000L)
        assertEquals(60, tracker.learnedFps)
    }
}
