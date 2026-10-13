package com.alkaidlab.sunshine.service

/** One aspect-preserving fit, after capturing the current display at its real size. */
object CaptureGeometry {
    data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int)

    fun fit(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Viewport {
        require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0)
        val wider = targetWidth.toLong() * sourceHeight > sourceWidth.toLong() * targetHeight
        val width = if (wider) (targetHeight.toLong() * sourceWidth / sourceHeight).toInt().coerceAtLeast(1) else targetWidth
        val height = if (wider) targetHeight else (targetWidth.toLong() * sourceHeight / sourceWidth).toInt().coerceAtLeast(1)
        return Viewport((targetWidth - width) / 2, (targetHeight - height) / 2, width, height)
    }
}
