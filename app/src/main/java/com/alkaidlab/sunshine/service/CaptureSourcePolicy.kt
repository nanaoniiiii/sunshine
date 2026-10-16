package com.alkaidlab.sunshine.service

/** Display 0 can be either ordinary primary capture or a tablet's native desktop. */
object CaptureSourcePolicy {
    data class Rotation(val follow: Boolean, val compatibility: Boolean)
    fun rotation(displayId: Int, desktop: Boolean, follow: Boolean, compatibility: Boolean): Rotation {
        val primaryCapture = displayId == 0 && !desktop
        return Rotation(primaryCapture && follow && !compatibility, primaryCapture && compatibility)
    }
}
