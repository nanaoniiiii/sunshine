package com.alkaidlab.sunshine.service

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.flow.StateFlow

/**
 * 采集画布尺寸同步：物理屏尺寸查询（displayCaptureSize）+ 采集画布 resize 与失败回滚（syncCaptureSize）。
 *
 * 注意：同包已有七鬼写的 `object CaptureGeometry`（等比适配/触摸映射纯函数），
 * 为避免重名，本类命名为 CaptureCanvasSync。
 *
 * 从 SunshineRuntimeManager 拆出（God object 增量拆分第 3 步）。
 * 实现与原内联代码逐字一致；所有外部可变状态经 lambda 在调用时读取，读取时机与原代码相同：
 * - displayCaptureSize：虚拟桌面优先 → Display.getRealSize → WindowManager 最大窗口 → 兜底参数；
 * - syncCaptureSize：尺寸未变只做 configureCanvas；变化则先摘掉 surface → resize → 挂回新 surface，
 *   异常时回滚到旧尺寸。返回 true 表示采集面与当前物理屏一致（或已同步），false 表示失败。
 */
internal class CaptureCanvasSync(
    private val context: () -> Context?,
    private val displayManager: () -> DisplayManager?,
    private val virtualScreenState: () -> StateFlow<VirtualScreenState>,
    private val virtualDisplay: () -> VirtualDisplay?,
    private val frameDispatcher: SunshineFrameDispatcher,
    private val engineClient: () -> SunshineEngineClient?,
    private val autoRotateEnabled: () -> Boolean,
    private val rotationCompatEnabled: () -> Boolean,
) {
    fun displayCaptureSize(fallbackWidth: Int, fallbackHeight: Int): IntArray {
        virtualScreenState().value.takeIf { it.active && it.mode == "desktop" }?.let { return intArrayOf(it.width, it.height) }
        val primarySize = Point()
        @Suppress("DEPRECATION") displayManager()?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(primarySize)
        if (primarySize.x > 0 && primarySize.y > 0) return intArrayOf(primarySize.x, primarySize.y)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context()?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val bounds = manager?.maximumWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0)
                return intArrayOf(bounds.width(), bounds.height())
        }
        val size = Point()
        displayManager()?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(size)
        return if (size.x > 0 && size.y > 0) intArrayOf(size.x, size.y)
        else intArrayOf(fallbackWidth.coerceAtLeast(1), fallbackHeight.coerceAtLeast(1))
    }

    /** A low-resolution or differently oriented first client must never shrink later clients. */
    fun syncCaptureSize(): Boolean {
        if ((virtualScreenState().value.active && virtualScreenState().value.mode == "desktop") || virtualScreenState().value.busy) return true
        val display = virtualDisplay() ?: return false
        if (!frameDispatcher.isRunning) return false
        frameDispatcher.sourceRotation = displayManager()?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        val size = displayCaptureSize(frameDispatcher.sourceWidth, frameDispatcher.sourceHeight)
        if (size[0] == frameDispatcher.sourceWidth && size[1] == frameDispatcher.sourceHeight) {
            engineClient()?.configureCanvas(size[0], size[1], autoRotateEnabled(), rotationCompatEnabled())
            return true
        }
        val oldWidth = frameDispatcher.sourceWidth
        val oldHeight = frameDispatcher.sourceHeight
        try {
            display.surface = null
            val resizedSurface = frameDispatcher.resizeCaptureSync(size[0], size[1])
                ?: throw IllegalStateException("Capture surface resize failed")
            display.resize(size[0], size[1], context()?.resources?.displayMetrics?.densityDpi ?: 300)
            display.surface = resizedSurface
            engineClient()?.configureCanvas(size[0], size[1], autoRotateEnabled(), rotationCompatEnabled())
            SunshineLogManager.log("VD", "Capture resized to ${size[0]}x${size[1]} (display pixels)")
            return true
        } catch (e: Exception) {
            try {
                display.resize(oldWidth, oldHeight, context()?.resources?.displayMetrics?.densityDpi ?: 300)
                display.surface = frameDispatcher.resizeCaptureSync(oldWidth, oldHeight)
            } catch (_: Exception) {}
            SunshineLogManager.log("VD_ERR", "Capture resize failed: ${e.message}")
            return false
        }
    }
}
