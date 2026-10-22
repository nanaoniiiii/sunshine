package com.alkaidlab.sunshine.controller

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.alkaidlab.sunshine.ui.VirtualDesktopActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** Reads controls only when navigating. No key-filter flag, screenshots, or polling. */
class ControllerNavigationService : AccessibilityService() {
    companion object {
        @Volatile private var instance: ControllerNavigationService? = null
        private val main = Handler(Looper.getMainLooper())
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected
        fun navigate(command: String, displayId: Int, fallback: () -> Unit) { main.post {
            val applied = try { instance?.move(command, displayId) == true } catch (error: Exception) {
                ControllerAdapter.navigationOutcome("", "控件导航未能完成：${error.javaClass.simpleName}")
                android.util.Log.w("SunshineNavigation", "Navigation failed", error)
                false
            }
            if (!applied) fallback()
        } }
        fun global(action: Int, displayId: Int, fallback: () -> Unit) { main.post {
            if (displayId != 0 || instance?.performGlobalAction(action) != true) fallback()
        } }
        fun hideFocus() { main.post { instance?.clearFocus() } }
        fun showWheel(displayId: Int, title: String, items: List<RadialItem>, source: String, ready: (Boolean) -> Unit) { main.post {
            ready(try { instance?.openWheel(displayId, title, items, source) == true } catch (_: Exception) { false })
        } }
        fun selectWheel(index: Int) { main.post { instance?.wheel?.select(index) } }
        fun hideWheel() { main.post { instance?.closeWheel() } }
        fun showMouseCursor(displayId: Int, x: Float, y: Float) { main.post { instance?.showMouseCursor(displayId, x, y) } }
        fun hideMouseCursor() { main.post { instance?.clearMouseCursor() } }
    }
    private var frame: FocusFrame? = null
    private var frameManager: WindowManager? = null
    private var frameDisplay = -1
    private var wheel: RadialWheelView? = null
    private var wheelManager: WindowManager? = null
    private var mouseCursor: MouseCursorView? = null
    private var mouseCursorManager: WindowManager? = null
    private var mouseCursorDisplay = -1
    private var cursor: Rect? = null
    private var cursorWindow = -1
    override fun onServiceConnected() { instance = this; _connected.value = true; ControllerAdapter.navigationChanged(true) }
    override fun onInterrupt() { ControllerAdapter.navigationInterrupted(); clearFocus(); closeWheel(); clearMouseCursor() }
    override fun onDestroy() { clearFocus(); closeWheel(); clearMouseCursor(); if (instance === this) { instance = null; _connected.value = false; ControllerAdapter.navigationChanged(false) }; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.windowId != cursorWindow &&
            event.className != RadialWheelView::class.java.name && event.className != FocusFrame::class.java.name && event.className != "android.widget.Toast") {
            cursor = null; cursorWindow = -1; clearFocus()
        }
    }
    private data class Candidate(val node: AccessibilityNodeInfo, val bounds: Rect)
    @Suppress("DEPRECATION")
    private fun move(command: String, displayId: Int): Boolean {
        if (!ControllerAdapter.status.value.running || VirtualDesktopActivity.foregroundDisplayId == displayId) return false
        val windows = if (Build.VERSION.SDK_INT >= 30) windowsOnAllDisplays.get(displayId).orEmpty() else if (displayId == 0) this.windows else emptyList()
        val window = windows.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.firstOrNull { it.isFocused }
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return false
        val root = window.root ?: return false
        val candidates = mutableListOf<Candidate>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo) {
            if (++visited > 700) { node.recycle(); return }
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            if (node.isVisibleToUser && node.isEnabled && !bounds.isEmpty && (node.isClickable || node.isCheckable || node.isLongClickable || node.isFocusable && node.childCount == 0)) {
                candidates.add(Candidate(node, bounds))
            } else node.recycle()
            // Traversal uses a separate reference so recycling candidates cannot invalidate children.
        }
        fun walk(node: AccessibilityNodeInfo) {
            if (visited >= 700) { node.recycle(); return }
            val children = (0 until node.childCount).mapNotNull { node.getChild(it) }
            visit(node)
            children.forEach { walk(it) }
        }
        walk(root)
        try {
            if (candidates.isEmpty()) { ControllerAdapter.navigationOutcome("", "当前界面没有可选择控件，可切换鼠标模式"); return false }
            val current = if (cursorWindow == window.id && cursor != null) candidates.minByOrNull { distance(it.bounds, cursor!!) }
                else candidates.firstOrNull { it.node.isFocused } ?: candidates.firstOrNull { it.node.isSelected } ?: candidates.first()
            current ?: return false
            fun label(candidate: Candidate): String = if (candidate.node.isPassword) "密码输入框" else (candidate.node.text ?: candidate.node.contentDescription ?: candidate.node.className ?: "控件").toString().take(60)
            if (command == "confirm") {
                cursor = Rect(current.bounds); cursorWindow = window.id; showFocus(displayId, current.bounds)
                val clicked = current.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked) current.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                ControllerAdapter.navigationOutcome(label(current), if (clicked) "已触发控件点击" else "交给系统确定键")
                return clicked
            }
            val parentMenu = command == "previous_menu" || command == "next_menu"
            val direction = when (command) { "previous_menu" -> "left"; "next_menu" -> "right"; else -> command }
            var origin = current
            var pool = candidates
            if (parentMenu) {
                val above = candidates.filter { it.bounds.bottom <= current.bounds.top + 4 && it.bounds.height() < resources.displayMetrics.heightPixels * .25f }
                if (above.isNotEmpty()) {
                    val nearestY = above.maxOf { it.bounds.centerY() }
                    val row = above.filter { abs(it.bounds.centerY() - nearestY) < maxOf(24, it.bounds.height() / 2) }
                    origin = row.firstOrNull { it.node.isSelected } ?: row.minBy { abs(it.bounds.centerX() - current.bounds.centerX()) }
                    pool = row.toMutableList()
                }
            }
            val target = pool.filter { it !== origin }.mapNotNull { candidate ->
                val dx = candidate.bounds.exactCenterX() - origin.bounds.exactCenterX()
                val dy = candidate.bounds.exactCenterY() - origin.bounds.exactCenterY()
                val primary = when (direction) { "left" -> -dx; "right" -> dx; "up" -> -dy; "down" -> dy; else -> return false }
                if (primary <= 2f) null else {
                    val cross = if (direction == "left" || direction == "right") abs(dy) else abs(dx)
                    candidate to (primary + cross * 3f)
                }
            }.minByOrNull { it.second }?.first ?: run {
                // Scroll native collections before giving up at a viewport boundary.
                val scroll = if (direction == "up" || direction == "left") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                var ancestor = current.node.parent
                while (ancestor != null) {
                    val parent = ancestor.parent
                    val applied = ancestor.isScrollable && ancestor.performAction(scroll)
                    ancestor.recycle(); if (applied) { parent?.recycle(); return true }; ancestor = parent
                }
                ControllerAdapter.navigationOutcome(label(current), "已到当前层级边界，交给系统方向键")
                return false
            }
            cursor = Rect(target.bounds); cursorWindow = window.id
            target.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (parentMenu && (target.node.className?.contains("Tab", true) == true || target.node.collectionItemInfo?.isSelected == true || origin.node.isSelected))
                target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            showFocus(displayId, target.bounds)
            ControllerAdapter.navigationOutcome(label(target), "焦点已移动")
            return true
        } finally { candidates.forEach { it.node.recycle() }; windows.forEach { it.recycle() } }
    }
    private fun distance(first: Rect, second: Rect) = abs(first.centerX() - second.centerX()) + abs(first.centerY() - second.centerY()) + abs(first.width() - second.width())
    private fun manager(displayId: Int): Pair<Context, WindowManager>? {
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(displayId) ?: return null
        val context = createDisplayContext(display)
        return context to (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
    }
    private fun params() = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; title = "Sunshine 手柄焦点"; if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0) }
    private fun showFocus(displayId: Int, bounds: Rect) {
        if (frameDisplay != displayId) clearFocus()
        if (frame == null) {
            val (context, manager) = manager(displayId) ?: return
            frame = FocusFrame(context); frameManager = manager; frameDisplay = displayId
            manager.addView(frame, params())
        }
        frame?.move(bounds)
    }
    private fun clearFocus() { frame?.cancel(); try { frameManager?.removeViewImmediate(frame) } catch (_: Exception) {}; frame = null; frameManager = null; frameDisplay = -1 }
    private fun openWheel(displayId: Int, title: String, items: List<RadialItem>, source: String): Boolean {
        closeWheel()
        clearMouseCursor()
        val (context, manager) = manager(displayId) ?: return false
        wheel = RadialWheelView(context, title, items, source); wheelManager = manager
        manager.addView(wheel, params().apply { this.title = "Sunshine 快捷圆环" })
        return true
    }
    private fun closeWheel() { try { wheelManager?.removeViewImmediate(wheel) } catch (_: Exception) {}; wheel = null; wheelManager = null }
    private fun showMouseCursor(displayId: Int, x: Float, y: Float) {
        if (displayId < 0 || wheel != null) return
        if (mouseCursorDisplay != displayId) clearMouseCursor()
        if (mouseCursor == null) {
            val (context, manager) = manager(displayId) ?: return
            val view = MouseCursorView(context)
            try { manager.addView(view, params().apply { title = "Sunshine 鼠标光标" }) }
            catch (_: Exception) { return }
            mouseCursor = view; mouseCursorManager = manager; mouseCursorDisplay = displayId
        }
        mouseCursor?.move(x, y)
    }
    private fun clearMouseCursor() {
        try { mouseCursorManager?.removeViewImmediate(mouseCursor) } catch (_: Exception) {}
        mouseCursor = null; mouseCursorManager = null; mouseCursorDisplay = -1
    }
    private class MouseCursorView(context: Context) : View(context) {
        private val scale = resources.displayMetrics.density
        private val shape = Path().apply {
            moveTo(0f, 0f); lineTo(0f, 27f); lineTo(6.8f, 21f)
            lineTo(11.4f, 31f); lineTo(16.2f, 28.7f); lineTo(11.5f, 18.8f)
            lineTo(20f, 18.8f); close()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 20, 30); style = Paint.Style.STROKE; strokeWidth = 2.2f; strokeJoin = Paint.Join.ROUND }
        private var x = 0f
        private var y = 0f
        fun move(x: Float, y: Float) { this.x = x; this.y = y; invalidate() }
        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            canvas.drawPath(shape, outline)
            canvas.drawPath(shape, fill)
            canvas.restore()
        }
    }
    private class FocusFrame(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2.5f * resources.displayMetrics.density }
        private var rectangle = RectF()
        private var animator: ValueAnimator? = null
        fun cancel() { animator?.cancel() }
        fun move(target: Rect) {
            animator?.cancel()
            val destination = RectF(target).apply { inset(-3f, -3f) }
            if (rectangle.isEmpty) rectangle.set(destination)
            val before = RectF(rectangle)
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                addUpdateListener { value -> val t = value.animatedValue as Float
                    rectangle.set(before.left + (destination.left - before.left) * t, before.top + (destination.top - before.top) * t,
                        before.right + (destination.right - before.right) * t, before.bottom + (destination.bottom - before.bottom) * t); invalidate() }
                start()
            }
        }
        override fun onDraw(canvas: Canvas) {
            paint.color = Color.argb(210, 5, 14, 28); paint.strokeWidth = 5f * resources.displayMetrics.density
            canvas.drawRoundRect(rectangle, 12f * resources.displayMetrics.density, 12f * resources.displayMetrics.density, paint)
            paint.color = Color.WHITE; paint.strokeWidth = 2.5f * resources.displayMetrics.density
            canvas.drawRoundRect(rectangle, 12f * resources.displayMetrics.density, 12f * resources.displayMetrics.density, paint)
        }
    }
}
