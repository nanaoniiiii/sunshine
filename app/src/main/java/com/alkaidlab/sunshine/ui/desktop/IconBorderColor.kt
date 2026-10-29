package com.alkaidlab.sunshine.ui.desktop

/** Dominant visible outer-rim colour; interior pixels and transparent padding do not vote. */
object IconBorderColor {
    fun extract(pixels: IntArray, width: Int, height: Int): IconPalette.Result {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val rim = linkedSetOf<Int>()
        fun opaque(index: Int) = pixels[index] ushr 24 >= 128
        for (y in 0 until height) {
            (0 until width).firstOrNull { opaque(y * width + it) }?.let { rim.add(y * width + it) }
            (width - 1 downTo 0).firstOrNull { opaque(y * width + it) }?.let { rim.add(y * width + it) }
        }
        for (x in 0 until width) {
            (0 until height).firstOrNull { opaque(it * width + x) }?.let { rim.add(it * width + x) }
            (height - 1 downTo 0).firstOrNull { opaque(it * width + x) }?.let { rim.add(it * width + x) }
        }
        if (rim.isEmpty()) return IconPalette.Result(0xff29344b.toInt(), 0f, false)
        val bins = linkedMapOf<Int, LongArray>()
        for (index in rim) {
            val p = pixels[index]
            val key = ((p ushr 19 and 31) shl 10) or ((p ushr 11 and 31) shl 5) or (p ushr 3 and 31)
            val bin = bins.getOrPut(key) { LongArray(4) }
            bin[0]++
            bin[1] = bin[1] + (p ushr 16 and 255).toLong()
            bin[2] = bin[2] + (p ushr 8 and 255).toLong()
            bin[3] = bin[3] + (p and 255).toLong()
        }
        val winner = bins.values.maxBy { it[0] }
        val n = winner[0]
        val color = 0xff000000.toInt() or ((winner[1] / n).toInt() shl 16) or
            ((winner[2] / n).toInt() shl 8) or (winner[3] / n).toInt()
        return IconPalette.Result(color, n.toFloat() / rim.size, true)
    }
}
