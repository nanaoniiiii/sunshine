package com.alkaidlab.sunshine.ui.desktop

import kotlin.math.sqrt

/** Extends local edge colours; it never filters the source artwork. */
object IconEdgeExtension {
    data class Result(val pixels: IntArray, val width: Int, val height: Int)

    fun create(source: IntArray, width: Int, height: Int, padding: Int): Result {
        require(width > 0 && height > 0 && source.size == width * height && padding > 0)
        val outputWidth = width + padding * 2
        val outputHeight = height + padding * 2
        val nearest = IntArray(outputWidth * outputHeight) { -1 }
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * width + x
            if (source[index] ushr 24 >= 128) nearest[(y + padding) * outputWidth + x + padding] = index
        }

        // Propagate edge sites rather than colours, so red and blue edges stay
        // red and blue instead of becoming a single averaged palette halo.
        fun distance(site: Int, x: Int, y: Int): Int {
            val dx = x - (site % width + padding)
            val dy = y - (site / width + padding)
            return dx * dx + dy * dy
        }
        fun visit(x: Int, y: Int, dx: Int, dy: Int) {
            val otherX = x + dx
            val otherY = y + dy
            if (otherX !in 0 until outputWidth || otherY !in 0 until outputHeight) return
            val candidate = nearest[otherY * outputWidth + otherX]
            if (candidate < 0) return
            val index = y * outputWidth + x
            val current = nearest[index]
            if (current < 0 || distance(candidate, x, y) < distance(current, x, y)) nearest[index] = candidate
        }
        for (y in 0 until outputHeight) for (x in 0 until outputWidth) {
            visit(x, y, -1, 0); visit(x, y, -1, -1); visit(x, y, 0, -1); visit(x, y, 1, -1)
        }
        for (y in outputHeight - 1 downTo 0) for (x in outputWidth - 1 downTo 0) {
            visit(x, y, 1, 0); visit(x, y, 1, 1); visit(x, y, 0, 1); visit(x, y, -1, 1)
        }
        // A nearest-site colour field alone contains Voronoi boundaries: a
        // pixel may abruptly switch from a black edge to a coloured corner.
        // Integral images blend neighbouring edge colours over a radius that
        // grows with distance. The original icon is drawn separately, sharp.
        val stride = outputWidth + 1
        val channels = Array(3) { IntArray(stride * (outputHeight + 1)) }
        for (y in 0 until outputHeight) {
            val sums = IntArray(3)
            for (x in 0 until outputWidth) {
                val site = nearest[y * outputWidth + x]
                val colour = if (site >= 0) source[site] else 0
                for (channel in 0..2) {
                    sums[channel] += colour ushr (16 - channel * 8) and 255
                    channels[channel][(y + 1) * stride + x + 1] = channels[channel][y * stride + x + 1] + sums[channel]
                }
            }
        }
        fun average(channel: Int, x: Int, y: Int, radius: Int): Float {
            val left = (x - radius).coerceAtLeast(0)
            val top = (y - radius).coerceAtLeast(0)
            val right = (x + radius + 1).coerceAtMost(outputWidth)
            val bottom = (y + radius + 1).coerceAtMost(outputHeight)
            val values = channels[channel]
            return (values[bottom * stride + right] - values[top * stride + right] -
                values[bottom * stride + left] + values[top * stride + left]).toFloat() / ((right - left) * (bottom - top))
        }
        val pixels = IntArray(nearest.size)
        nearest.forEachIndexed { index, site ->
            if (site >= 0) {
                val x = index % outputWidth
                val y = index / outputWidth
                val edgeDistance = sqrt(distance(site, x, y).toFloat())
                val fraction = (edgeDistance / padding).coerceIn(0f, 1f)
                // Smoothstep has zero slope at both ends: no seam at the icon,
                // no visible rectangular boundary at the extent of the field.
                val opacity = 1f - fraction * fraction * (3f - 2f * fraction)
                if (opacity > 0f) {
                    val radius = (edgeDistance * .65f).coerceAtMost(padding * .5f)
                    val wholeRadius = radius.toInt()
                    val mix = radius - wholeRadius
                    var colour = 0
                    for (channel in 0..2) {
                        val inner = average(channel, x, y, wholeRadius)
                        val outer = average(channel, x, y, wholeRadius + 1)
                        val value = (inner + (outer - inner) * mix + .5f).toInt().coerceIn(0, 255)
                        colour = colour or (value shl (16 - channel * 8))
                    }
                    pixels[index] = ((opacity * 255f + .5f).toInt() shl 24) or colour
                }
            }
        }
        return Result(smoothExtension(pixels, outputWidth, outputHeight), outputWidth, outputHeight)
    }

    /** Smooth the generated field, including alpha, without sampling the sharp icon. */
    private fun smoothExtension(pixels: IntArray, width: Int, height: Int): IntArray {
        var field = Array(4) { FloatArray(pixels.size) }
        var scratch = Array(4) { FloatArray(pixels.size) }
        pixels.forEachIndexed { index, pixel ->
            val alpha = (pixel ushr 24).toFloat()
            field[0][index] = alpha
            for (channel in 1..3) field[channel][index] = (pixel ushr (24 - channel * 8) and 255) * alpha / 255f
        }
        val weights = intArrayOf(1, 4, 6, 4, 1)
        repeat(2) {
            for (horizontal in listOf(true, false)) {
                for (y in 0 until height) for (x in 0 until width) {
                    val index = y * width + x
                    var alpha = 0f
                    var red = 0f
                    var green = 0f
                    var blue = 0f
                    for (tap in -2..2) {
                        val sampleX = if (horizontal) (x + tap).coerceIn(0, width - 1) else x
                        val sampleY = if (horizontal) y else (y + tap).coerceIn(0, height - 1)
                        val sample = sampleY * width + sampleX
                        val weight = weights[tap + 2]
                        alpha += field[0][sample] * weight
                        red += field[1][sample] * weight
                        green += field[2][sample] * weight
                        blue += field[3][sample] * weight
                    }
                    scratch[0][index] = alpha / 16f
                    scratch[1][index] = red / 16f
                    scratch[2][index] = green / 16f
                    scratch[3][index] = blue / 16f
                }
                val previous = field
                field = scratch
                scratch = previous
            }
        }
        return IntArray(pixels.size) { index ->
            val alpha = field[0][index]
            if (alpha < .001f) 0 else {
                // Keep the finite texture boundary fully transparent after
                // convolution, so the field cannot expose a rectangular rim.
                val x = index % width
                val y = index / width
                val boundary = minOf(x, width - 1 - x, y, height - 1 - y)
                val fade = (boundary / 4f).coerceIn(0f, 1f)
                val feather = fade * fade * (3f - 2f * fade)
                var colour = ((alpha * feather + .5f).toInt().coerceIn(0, 255) shl 24)
                for (channel in 1..3) colour = colour or (
                    (field[channel][index] * 255f / alpha + .5f).toInt().coerceIn(0, 255) shl (24 - channel * 8))
                colour
            }
        }
    }
}
