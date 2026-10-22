package com.alkaidlab.sunshine.controller

import android.content.ComponentName
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.*

/** Drawn only while a trigger is held. No focus, screenshot capture, or permanent control window. */
class RadialWheelView(context: Context, private val title: String, private val items: List<RadialItem>, private val source: String) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icons = mutableMapOf<String, Drawable>()
    private var iconScope: CoroutineScope? = null
    private var selected = -1
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Draw the wheel immediately; package icon decoding must not delay
        // the overlay's first frame or its ready callback.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { iconScope = it }
        scope.launch {
            items.forEach { item ->
                val component = item.component?.let { ComponentName.unflattenFromString(it) } ?: return@forEach
                val icon = runCatching { context.packageManager.getActivityIcon(component) }.getOrNull() ?: return@forEach
                withContext(Dispatchers.Main) {
                    if (isAttachedToWindow) { icons[item.id] = icon; invalidate() }
                }
            }
        }
    }
    override fun onDetachedFromWindow() {
        iconScope?.cancel()
        iconScope = null
        super.onDetachedFromWindow()
    }
    fun select(index: Int) { if (selected != index) { selected = index; invalidate() } }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.argb(80, 0, 0, 0))
        val cx = width / 2f; val cy = height / 2f
        val radius = min(min(width, height) * .36f, 245 * dp)
        val track = RectF(cx - radius * .75f, cy - radius * .75f, cx + radius * .75f, cy + radius * .75f)
        val count = items.size.coerceAtLeast(1)
        val step = 360f / count
        paint.style = Paint.Style.STROKE; paint.strokeWidth = radius * .42f
        items.forEachIndexed { index, _ ->
            paint.color = if (index == selected) Color.argb(245, 118, 218, 255) else Color.argb(232, 29, 38, 55)
            canvas.drawArc(track, -90 + index * step - step / 2 + 1, step - 2, false, paint)
        }
        paint.style = Paint.Style.FILL
        items.forEachIndexed { index, item ->
            val radians = (index * step - 90) * PI / 180
            val x = cx + cos(radians).toFloat() * radius * .75f
            val y = cy + sin(radians).toFloat() * radius * .75f
            val half = radius * .10f
            val icon = icons[item.id]
            if (icon != null) { icon.setBounds((x - half).toInt(), (y - half - 8 * dp).toInt(), (x + half).toInt(), (y + half - 8 * dp).toInt()); icon.draw(canvas) }
            else {
                paint.color = if (index == selected) Color.rgb(17, 30, 46) else Color.WHITE
                paint.textSize = 26 * dp; paint.textAlign = Paint.Align.CENTER
                if (item.id == "POWER") {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.3f * dp; paint.strokeCap = Paint.Cap.ROUND
                    canvas.drawArc(RectF(x - 11 * dp, y - 20 * dp, x + 11 * dp, y + 2 * dp), -55f, 290f, false, paint)
                    canvas.drawLine(x, y - 24 * dp, x, y - 10 * dp, paint)
                    paint.style = Paint.Style.FILL
                } else canvas.drawText(when (item.id) { "BACK" -> "←"; "HOME" -> "⌂"; "RECENTS" -> "▦"; "NOTIFICATIONS" -> "↓"; "MENU" -> "☰"; "MOUSE" -> "↖"; else -> "◇" }, x, y + 2 * dp, paint)
            }
            paint.color = if (index == selected) Color.rgb(17, 30, 46) else Color.WHITE
            paint.textAlign = Paint.Align.CENTER; paint.textSize = 12 * dp
            canvas.drawText(fit(item.label, radius * .52f), x, y + radius * .15f, paint)
        }
        paint.color = Color.argb(245, 17, 24, 37); canvas.drawCircle(cx, cy, radius * .43f, paint)
        paint.color = Color.WHITE; paint.textSize = 18 * dp; paint.textAlign = Paint.Align.CENTER
        canvas.drawText(if (items.isEmpty()) "暂无常用应用" else items.getOrNull(selected)?.label?.let { fit(it, radius * .8f) } ?: title, cx, cy - 5 * dp, paint)
        paint.color = Color.rgb(181, 194, 211); paint.textSize = 12 * dp
        canvas.drawText(if (selected >= 0) "松开扳机触发" else "摇杆选择 · 居中取消", cx, cy + 22 * dp, paint)
        paint.textSize = 11 * dp
        val footer = fit(source, width * .9f)
        val footerWidth = paint.measureText(footer)
        paint.color = Color.argb(215, 17, 24, 37)
        canvas.drawRoundRect(RectF(cx - footerWidth / 2 - 12 * dp, cy + radius + 15 * dp, cx + footerWidth / 2 + 12 * dp, cy + radius + 39 * dp), 12 * dp, 12 * dp, paint)
        paint.color = Color.WHITE
        canvas.drawText(footer, cx, cy + radius + 32 * dp, paint)
    }
    private fun fit(value: String, width: Float): String {
        if (paint.measureText(value) <= width) return value
        val length = paint.breakText(value, true, width - paint.measureText("…"), null)
        return value.take(length.coerceAtLeast(0)) + "…"
    }
}
