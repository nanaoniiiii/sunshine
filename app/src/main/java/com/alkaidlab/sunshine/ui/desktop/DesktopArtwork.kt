package com.alkaidlab.sunshine.ui.desktop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class DesktopArtwork(
    val image: ImageBitmap,
    val palette: IconPalette.Result,
)
object DesktopArtworkCache {
    private val cache = object : LruCache<String, DesktopArtwork>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: DesktopArtwork) = value.image.width * value.image.height * 4
    }
    @Synchronized fun clear() { cache.evictAll() }
    @Synchronized fun peek(key: String?): DesktopArtwork? = key?.let(cache::get)
    @Synchronized fun invalidate(packageName: String) {
        cache.snapshot().keys.filter { it.startsWith("$packageName/") }.forEach { cache.remove(it) }
    }
    @Synchronized fun get(key: String, load: () -> Drawable): DesktopArtwork = cache.get(key) ?: run {
        val icon = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        load().apply { setBounds(0, 0, 512, 512); draw(Canvas(icon)) }
        val sample = Bitmap.createScaledBitmap(icon, 96, 96, true)
        val pixels = IntArray(96 * 96); sample.getPixels(pixels, 0, 96, 0, 0, 96, 96)
        val palette = IconBorderColor.extract(pixels, 96, 96)
        sample.recycle()
        DesktopArtwork(icon.asImageBitmap(), palette).also { cache.put(key, it) }
    }
}
