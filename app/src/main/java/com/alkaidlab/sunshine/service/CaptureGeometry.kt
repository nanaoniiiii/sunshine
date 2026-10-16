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
    /** Rotate pixels only when the fixed receiver canvas and source have different orientations. */
    fun compatibilityTurn(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int,
                          displayRotation: Int, enabled: Boolean): Int {
        if (!enabled || sourceWidth == sourceHeight || targetWidth == targetHeight ||
            (sourceWidth > sourceHeight) == (targetWidth > targetHeight)) return 0
        return if (sourceWidth > sourceHeight) {
            if (displayRotation == 3) 3 else 1
        } else {
            if (displayRotation == 2) 1 else 3
        }
    }

    fun orientedFit(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int, turn: Int): Viewport =
        if (turn == 1 || turn == 3) fit(sourceHeight, sourceWidth, targetWidth, targetHeight)
        else fit(sourceWidth, sourceHeight, targetWidth, targetHeight)

    fun mapPosition(x: Float, y: Float, sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int,
                    turn: Int = 0): Pair<Float, Float> {
        val point = mapPositionUnclamped(x, y, sourceWidth, sourceHeight, targetWidth, targetHeight, turn)
        return point.first.coerceIn(0f, (sourceWidth - 1).toFloat()) to
            point.second.coerceIn(0f, (sourceHeight - 1).toFloat())
    }

    private fun mapPositionUnclamped(x: Float, y: Float, sourceWidth: Int, sourceHeight: Int,
                                     targetWidth: Int, targetHeight: Int, turn: Int): Pair<Float, Float> {
        val viewport = orientedFit(sourceWidth, sourceHeight, targetWidth, targetHeight, turn)
        val u = ((x - viewport.x) / viewport.width).coerceIn(0f, 1f)
        val v = ((y - viewport.y) / viewport.height).coerceIn(0f, 1f)
        return when (turn) {
            1 -> v * sourceWidth to (1f - u) * sourceHeight
            3 -> (1f - v) * sourceWidth to u * sourceHeight
            else -> u * sourceWidth to v * sourceHeight
        }
    }

    /** Invert encoder fitting first, then the system display's fit into the capture surface. */
    fun mapCapturedPosition(x: Float, y: Float, displayWidth: Int, displayHeight: Int,
                            captureWidth: Int, captureHeight: Int, targetWidth: Int, targetHeight: Int,
                            turn: Int = 0): Pair<Float, Float> {
        if (displayWidth == captureWidth && displayHeight == captureHeight)
            return mapPosition(x, y, captureWidth, captureHeight, targetWidth, targetHeight, turn)
        // Clamp only the final display pixels, so the outer edge remains reachable after scaling.
        val captured = mapPositionUnclamped(x, y, captureWidth, captureHeight, targetWidth, targetHeight, turn)
        return mapPosition(captured.first, captured.second, displayWidth, displayHeight, captureWidth, captureHeight)
    }
}
