package com.alkaidlab.sunshine.service

/** Monotonic per-client deadlines. A late frame skips missed slots instead of catching up. */
class FramePacer(fps: Int) {
    var fps: Int = fps.coerceIn(1, 240)
        private set
    var nextFrameNs: Long = 0L
        private set

    fun setFps(value: Int) {
        val updated = value.coerceIn(1, 240)
        if (updated != fps) {
            fps = updated
            nextFrameNs = 0L
        }
    }

    fun isDue(nowNs: Long): Boolean = nextFrameNs == 0L || nowNs >= nextFrameNs

    fun advance(nowNs: Long) {
        val periodNs = 1_000_000_000L / fps
        nextFrameNs = if (nextFrameNs == 0L) nowNs + periodNs
        else nextFrameNs + ((nowNs - nextFrameNs).coerceAtLeast(0L) / periodNs + 1) * periodNs
    }
}
