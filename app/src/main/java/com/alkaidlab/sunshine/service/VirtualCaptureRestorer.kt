package com.alkaidlab.sunshine.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Display
import android.view.Surface

/**
 * Restores physical-display capture after a virtual screen is torn down.
 *
 * Extracted from SunshineRuntimeManager (God object split, roadmap #6 step 2).
 * It is invoked on the main thread; all [Host] reads and writes execute on the
 * calling thread, exactly as the original inline code did.
 */
internal class VirtualCaptureRestorer(private val host: Host) {

    /** State accessors for the physical-capture restore path. */
    internal interface Host {
        val mediaProjection: MediaProjection?
        val context: Context?
        var virtualDisplay: VirtualDisplay?
        fun captureDisplaySize(fallbackWidth: Int, fallbackHeight: Int): IntArray
        fun lastKnownWidth(): Int
        fun lastKnownHeight(): Int
        fun setVirtualSourceActive(active: Boolean)
        fun resizeCapture(width: Int, height: Int): Surface?
        fun setSourceRotation(rotation: Int)
        fun physicalDisplayRotation(): Int
        fun configureCanvas(width: Int, height: Int)
    }

    /** Re-attach the frame pipeline to the phone's physical display. */
    fun restorePhysicalCapture() {
        host.setVirtualSourceActive(false)
        val mp = host.mediaProjection ?: return
        val dimensions = host.captureDisplaySize(host.lastKnownWidth(), host.lastKnownHeight())
        val surface = host.resizeCapture(dimensions[0], dimensions[1]) ?: return
        host.virtualDisplay?.release()
        host.virtualDisplay = mp.createVirtualDisplay(
            "SunshineVirtualDisplay", dimensions[0], dimensions[1],
            host.context?.resources?.displayMetrics?.densityDpi ?: 300,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
        host.setSourceRotation(host.physicalDisplayRotation())
        host.configureCanvas(dimensions[0], dimensions[1])
    }
}
