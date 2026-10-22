package com.alkaidlab.sunshine.ui.desktop

/** Analyze the entire icon, before cropping. Transparent pixels stay in the area denominator. */
object IconPalette {
    data class Result(val color: Int, val fraction: Float, val matched: Boolean)
    fun extract(pixels: IntArray): Result {
        if (pixels.isEmpty()) return Result(0xff29344b.toInt(), 0f, false)
        val histogram = mutableMapOf<Int, Int>()
        pixels.forEach { pixel ->
            if (pixel ushr 24 >= 128) {
                val key = ((pixel ushr 19 and 31) shl 10) or ((pixel ushr 11 and 31) shl 5) or (pixel ushr 3 and 31)
                histogram[key] = (histogram[key] ?: 0) + 1
            }
        }
        if (histogram.isEmpty()) return Result(0xff29344b.toInt(), 0f, false)
        data class Bin(val r: Float, val g: Float, val b: Float, val count: Int)
        val bins = histogram.map { (key, count) -> Bin((key ushr 10 and 31) * 8f + 4, (key ushr 5 and 31) * 8f + 4, (key and 31) * 8f + 4, count) }
        fun distance(bin: Bin, color: FloatArray) = (bin.r - color[0]) * (bin.r - color[0]) + (bin.g - color[1]) * (bin.g - color[1]) + (bin.b - color[2]) * (bin.b - color[2])
        val first = bins.maxBy { it.count }
        val centers = mutableListOf(floatArrayOf(first.r, first.g, first.b))
        repeat(minOf(5, bins.size - 1)) {
            val next = bins.maxBy { bin -> centers.minOf { distance(bin, it) } * kotlin.math.sqrt(bin.count.toFloat()) }
            centers.add(floatArrayOf(next.r, next.g, next.b))
        }
        var totals = IntArray(centers.size)
        repeat(8) {
            val sums = Array(centers.size) { FloatArray(3) }; totals = IntArray(centers.size)
            bins.forEach { bin ->
                val index = centers.indices.minBy { distance(bin, centers[it]) }
                totals[index] += bin.count
                sums[index][0] += bin.r * bin.count; sums[index][1] += bin.g * bin.count; sums[index][2] += bin.b * bin.count
            }
            centers.indices.forEach { index -> if (totals[index] > 0) for (channel in 0..2) centers[index][channel] = sums[index][channel] / totals[index] }
        }
        val eligible = centers.indices.filter { totals[it] > pixels.size * .10f && totals[it] < pixels.size * .50f }
        val chosen = eligible.maxByOrNull { index ->
            val color = centers[index]; val high = color.max(); val low = color.min()
            (high - low) * .65f + high * .12f + totals[index].toFloat() / pixels.size * 45
        } ?: centers.indices.maxBy { totals[it] }
        val color = centers[chosen]
        return Result(0xff000000.toInt() or (color[0].toInt().coerceIn(0,255) shl 16) or (color[1].toInt().coerceIn(0,255) shl 8) or color[2].toInt().coerceIn(0,255),
            totals[chosen].toFloat() / pixels.size, eligible.isNotEmpty())
    }
}
