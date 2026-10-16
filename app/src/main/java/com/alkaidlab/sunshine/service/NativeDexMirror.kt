package com.alkaidlab.sunshine.service

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface

/** A capture sink for a system-owned DeX display; never owns or releases that display. */
internal class NativeDexMirror private constructor(val source: Display, private val token: IBinder) {
    fun close() { Class.forName("android.view.SurfaceControl").getMethod("destroyDisplay", IBinder::class.java).invoke(null, token) }

    companion object {
        @SuppressLint("WrongConstant") // Samsung's service name is absent from the AOSP SDK constants.
        fun activeDisplay(context: Context): Display? { return try {
            val manager = context.getSystemService("desktopmode") ?: return null
            val state = manager.javaClass.getMethod("getDesktopModeState").invoke(manager) ?: return null
            val enabled = state.javaClass.getMethod("getEnabled").invoke(state) as Int
            val type = state.javaClass.getMethod("getDisplayType").invoke(state) as Int
            if (enabled != 4 || type !in listOf(101, 102)) return null
            val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            (if (type == 101) displays.getDisplay(Display.DEFAULT_DISPLAY) else
                displays.getDisplays("com.samsung.android.hardware.display.category.DESKTOP")
                    .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY } ?: displays.getDisplay(2))
                ?.takeIf { it.isValid && it.state == Display.STATE_ON }
        } catch (_: Exception) { null } }

        fun hasExternalConnection(context: Context): Boolean {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val pc = flag("FLAG_PC_DEX_DISPLAY")
            val wireless = flag("FLAG_WIRELESS_DEX_DISPLAY")
            return manager.displays.any { it.displayId != Display.DEFAULT_DISPLAY &&
                (displayType(it) == 2 || it.flags and (pc or wireless) != 0) }
        }

        private fun flag(name: String): Int = try { Display::class.java.getField(name).getInt(null) } catch (_: Exception) { 0 }
        private fun displayType(display: Display): Int = try { Display::class.java.getMethod("getType").invoke(display) as Int } catch (_: Exception) { 0 }

        fun create(source: Display, width: Int, height: Int, surface: Surface): NativeDexMirror {
            require(source.isValid && surface.isValid)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") source.getRealMetrics(metrics)
            val viewport = CaptureGeometry.fit(metrics.widthPixels, metrics.heightPixels, width, height)
            val sc = Class.forName("android.view.SurfaceControl")
            val token = sc.getMethod("createDisplay", String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, "Sunshine DeX Capture", false) as IBinder
            try {
                val type = Class.forName("android.view.SurfaceControl\$Transaction")
                val transaction = type.getConstructor().newInstance()
                try {
                    val stack = Display::class.java.getMethod("getLayerStack").invoke(source) as Int
                    type.getMethod("setDisplaySurface", IBinder::class.java, Surface::class.java).invoke(transaction, token, surface)
                    type.getMethod("setDisplayProjection", IBinder::class.java, Int::class.javaPrimitiveType, Rect::class.java, Rect::class.java)
                        .invoke(transaction, token, Surface.ROTATION_0, Rect(0, 0, metrics.widthPixels, metrics.heightPixels),
                            Rect(viewport.x, viewport.y, viewport.x + viewport.width, viewport.y + viewport.height))
                    type.getMethod("setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType).invoke(transaction, token, stack)
                    type.getMethod("apply").invoke(transaction)
                } finally { type.getMethod("close").invoke(transaction) }
                return NativeDexMirror(source, token)
            } catch (e: Exception) {
                sc.getMethod("destroyDisplay", IBinder::class.java).invoke(null, token)
                throw e
            }
        }
    }
}
