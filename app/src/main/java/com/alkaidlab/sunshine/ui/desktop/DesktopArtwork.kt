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
    val backgroundImage: ImageBitmap = image,
    val backgroundScale: Float = 1f,
)
object DesktopArtworkCache {
    private val cache = LruCache<String, DesktopArtwork>(12)
    @Synchronized fun clear() { cache.evictAll() }
    @Synchronized fun get(key: String, load: () -> Drawable): DesktopArtwork = cache.get(key) ?: run {
        val icon = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        load().apply { setBounds(0, 0, 512, 512); draw(Canvas(icon)) }
        val sample = Bitmap.createScaledBitmap(icon, 96, 96, true)
        val pixels = IntArray(96 * 96); sample.getPixels(pixels, 0, 96, 0, 0, 96, 96)
        val palette = IconPalette.extract(pixels)
        val edgeSample = Bitmap.createScaledBitmap(icon, 256, 256, true)
        val edgePixels = IntArray(256 * 256)
        edgeSample.getPixels(edgePixels, 0, 256, 0, 0, 256, 256)
        val extended = IconEdgeExtension.create(edgePixels, 256, 256, 64)
        val edgeImage = Bitmap.createBitmap(extended.pixels, extended.width, extended.height, Bitmap.Config.ARGB_8888)
        sample.recycle()
        edgeSample.recycle()
        // The untouched 512px icon is drawn above this colour field. The rail
        // uses that same untouched icon; only the outside colour field fades.
        DesktopArtwork(icon.asImageBitmap(), palette, edgeImage.asImageBitmap(), 1.5f).also { cache.put(key, it) }
    }
}
