package com.alkaidlab.sunshine.service

import kotlin.math.abs
import kotlin.math.ceil

/** Producer timestamps only; callback bursts or codec frame repeats cannot raise the limit. */
class SourceCadenceTracker {
    companion object {
        // Static: observe() runs per captured frame on the GL thread; avoid a list allocation each call.
        private val NOMINAL_RATES = intArrayOf(24, 25, 30, 48, 50, 60, 72, 75, 90, 96, 100, 120, 144, 165, 180, 200, 240)
    }
    var learnedFps = 0
        private set
    private var previous = 0L
    private var windowStart = 0L
    private var intervals = 0
    private var candidate = 0
    private var stableWindows = 0

    fun reset() {
        learnedFps = 0
        previous = 0
        windowStart = 0
        intervals = 0
        candidate = 0
        stableWindows = 0
    }

    fun observe(timestampNs: Long): Int? {
        if (timestampNs <= 0 || timestampNs == previous) return null
        if (previous != 0L && (timestampNs < previous || timestampNs - previous > 250_000_000L)) {
            windowStart = timestampNs
            intervals = 0
            candidate = 0
            stableWindows = 0
            previous = timestampNs
            return null
        }
        previous = timestampNs
        if (windowStart == 0L) { windowStart = timestampNs; return null }
        intervals++
        val elapsed = timestampNs - windowStart
        if (elapsed < 1_000_000_000L) return null
        val rate = intervals * 1_000_000_000.0 / elapsed
        windowStart = timestampNs
        intervals = 0
        if (rate !in 1.0..252.0) { candidate = 0; stableWindows = 0; return null }
        val nominal = NOMINAL_RATES.minBy { abs(it - rate) }
        val fps = if (abs(nominal - rate) / nominal <= .05) nominal else ceil(rate).toInt().coerceAtMost(240)
        if (fps == candidate) stableWindows++ else { candidate = fps; stableWindows = 1 }
        if (stableWindows < 2 || fps <= learnedFps) return null
        // Retain the demonstrated ceiling through static scenes. Lowering it on
        // every quiet period would repeatedly recreate the codec on animation.
        learnedFps = fps
        return fps
    }
}
