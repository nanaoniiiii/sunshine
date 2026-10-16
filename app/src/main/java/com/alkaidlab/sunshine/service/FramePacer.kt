package com.alkaidlab.sunshine.service

/** Monotonic per-client deadlines. A late frame skips missed slots instead of catching up. */
class FramePacer(fps: Int) {
    companion object {
        /** Re-encoding a 60 Hz virtual display at 120 fps only adds old frames. */
        fun effectiveFps(requested: Int, maximum: Int, virtual: Boolean, sourceHz: Float): Int {
            val sourceLimit = if (!virtual) 240 else if (sourceHz.isFinite() && sourceHz > 0f)
                kotlin.math.ceil(sourceHz.toDouble()).toInt().coerceIn(1, 240) else 60
            return minOf(requested.coerceIn(1, 240), maximum.coerceIn(1, 240), sourceLimit)
        }

        fun nextSubmissionNs(deadline: Long, previousPresentation: Long, sameTexture: Boolean,
                             repeat: Boolean, virtual: Boolean): Long? {
            if (!sameTexture) return deadline
            if (!repeat) return null
            // A fresh virtual-display buffer wakes the dispatcher immediately.
            // Keep a static stream alive without racing its next source buffer
            // and needlessly re-encoding the previous texture at the full rate.
            return if (virtual) maxOf(deadline, previousPresentation + 250_000_000L) else deadline
        }
    }
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
