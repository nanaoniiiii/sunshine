package com.alkaidlab.sunshine.ui.screens

import android.content.Context
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alkaidlab.sunshine.controller.*
import com.alkaidlab.sunshine.ui.components.*
import com.alkaidlab.sunshine.ui.theme.*
import java.util.Locale

@Composable
fun ControllerTestScreen(onBack: () -> Unit) {
    val status by ControllerAdapter.status.collectAsState()
    val live by ControllerAdapter.live.collectAsState()
    val trace by ControllerAdapter.trace.collectAsState()
    var mouseProbe by remember { mutableStateOf(MouseProbe()) }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { ControllerAdapter.refreshLive() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("返回实验室") }
            PageHeader("手柄测试", "实时查看模式、输入和触发的操作")
        }
        item { SectionCard("适配器状态", Icons.Default.SportsEsports) {
            Text(if (status.ready) "正在接管" else if (status.running) "等待输入服务" else "Sunshine 服务未启动", style = MaterialTheme.typography.titleLarge)
            Text(status.detail, color = TextSecondary)
            Text("控件层级导航：${if (status.systemNavigation) "已连接" else "未连接"}\nRT 圆环来源：${status.frequentAppsSource}", color = TextSecondary)
            Text("鼠标注入：${status.mouseInjection}", color = TextSecondary)
            if (status.focusedControl.isNotBlank() || status.navigationResult.isNotBlank()) Text("当前焦点：${status.focusedControl}\n${status.navigationResult}", color = TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControllerMode.values().forEach { mode -> OutlinedButton(onClick = { ControllerAdapter.mode(mode) }, enabled = status.ready) { Text(mode.title) } }
            }
            Text("L3：导航 ⇄ 鼠标　R3：系统映射 ⇄ 游戏原生\n主页：恢复系统映射并回主页", color = TextSecondary)
            Text("LB / RB：上级菜单切换\nLT / RT 按下即打开：快捷操作 / 常用应用圆环\n按住保持，左右任一摇杆选中；松开扳机触发，居中松开取消", color = TextSecondary)
            Text("物理输入报告：${status.physicalReports}　内核 → 映射：${status.kernelToMapperMs?.let { "$it ms" } ?: "等待输入"}　最大：${status.maxKernelToMapperMs?.let { "$it ms" } ?: "—"}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Text("时间统计只包含主机内核事件到映射处理，不含蓝牙、串流网络、游戏处理和画面显示。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        } }
        if (live.isEmpty()) item { Text("连接手柄或在 Moonlight 接收端操作后，这里会显示实时状态。", color = TextSecondary) }
        item { SectionCard("鼠标点击测试", Icons.Default.Mouse) {
            Text("用 L3 进入鼠标模式，移动左摇杆到下方测试区，分别按 A、B，也可同时按住。数字来自测试区实际收到的 Android MotionEvent。", color = TextSecondary)
            val padColor = MaterialTheme.colorScheme.primaryContainer.toArgb()
            val labelColor = MaterialTheme.colorScheme.onPrimaryContainer.toArgb()
            AndroidView(factory = { context -> MouseEventProbeView(context) { event, channel ->
                mouseProbe = mouseProbe.record(event, channel)
            } }, update = { it.setColors(padColor, labelColor) }, modifier = Modifier.fillMaxWidth().height(110.dp))
            Text("主键按下 / 松开：${mouseProbe.primaryPresses} / ${mouseProbe.primaryReleases}　副键按下 / 松开：${mouseProbe.secondaryPresses} / ${mouseProbe.secondaryReleases}", color = TextSecondary)
            Text("鼠标 DOWN / UP：${mouseProbe.downs} / ${mouseProbe.ups}　触屏点击：${mouseProbe.touchTaps}", color = TextSecondary)
            Text("最近收到：${mouseProbe.lastEvent}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        } }
        items(live, key = { it.id }) { controller -> SectionCard(controller.name, Icons.Default.Gamepad) {
            Text("当前模式：${controller.mode.title}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 380.dp) 4 else 5
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in ControllerButtons.tokens.entries.toList().chunked(columns)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (mask, label) ->
                    val active = controller.sample.buttons and mask != 0
                    Box(Modifier.weight(1f).background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 12.sp, maxLines = 1, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
            }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StickIndicator("左摇杆", controller.sample.lx, controller.sample.ly)
                StickIndicator("右摇杆", controller.sample.rx, controller.sample.ry)
            }
            Text(String.format(Locale.ROOT, "LT %.2f     RT %.2f", controller.sample.lt, controller.sample.rt), color = TextSecondary)
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("最近触发的操作", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = ControllerAdapter::clearTrace) { Text("清空记录") }
        } }
        if (trace.isEmpty()) item { Text("按下按键、移动摇杆或切换模式开始测试。", color = TextSecondary) }
        items(trace.asReversed()) { event ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${event.input} → ${event.operation}", style = MaterialTheme.typography.titleMedium)
                    Text("${event.device} · ${event.mode?.title ?: "系统"} · 运行 ${event.time / 1000}.${(event.time % 1000).toString().padStart(3, '0')} s", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
        }
    }
}

private data class MouseProbe(
    val primaryPresses: Int = 0, val primaryReleases: Int = 0,
    val secondaryPresses: Int = 0, val secondaryReleases: Int = 0,
    val downs: Int = 0, val ups: Int = 0, val touchTaps: Int = 0,
    val lastEvent: String = "等待鼠标或触屏事件",
) {
    fun record(event: MotionEvent, channel: String): MouseProbe {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_MOVE) return this
        val mouse = event.isFromSource(InputDevice.SOURCE_MOUSE)
        val button = event.actionButton
        val actionName = when (action) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_BUTTON_PRESS -> "BUTTON_PRESS"
            MotionEvent.ACTION_BUTTON_RELEASE -> "BUTTON_RELEASE"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "action=$action"
        }
        val buttonName = when (button) { MotionEvent.BUTTON_PRIMARY -> "主键"; MotionEvent.BUTTON_SECONDARY -> "副键"; else -> "$button" }
        return copy(
            primaryPresses = primaryPresses + if (mouse && action == MotionEvent.ACTION_BUTTON_PRESS && button == MotionEvent.BUTTON_PRIMARY) 1 else 0,
            primaryReleases = primaryReleases + if (mouse && action == MotionEvent.ACTION_BUTTON_RELEASE && button == MotionEvent.BUTTON_PRIMARY) 1 else 0,
            secondaryPresses = secondaryPresses + if (mouse && action == MotionEvent.ACTION_BUTTON_PRESS && button == MotionEvent.BUTTON_SECONDARY) 1 else 0,
            secondaryReleases = secondaryReleases + if (mouse && action == MotionEvent.ACTION_BUTTON_RELEASE && button == MotionEvent.BUTTON_SECONDARY) 1 else 0,
            downs = downs + if (mouse && action == MotionEvent.ACTION_DOWN) 1 else 0,
            ups = ups + if (mouse && action == MotionEvent.ACTION_UP) 1 else 0,
            touchTaps = touchTaps + if (!mouse && action == MotionEvent.ACTION_UP) 1 else 0,
            lastEvent = "$channel · ${if (mouse) "鼠标" else "触屏"} $actionName · actionButton=$buttonName · buttons=${event.buttonState}",
        )
    }
}

private class MouseEventProbeView(context: Context, private val onEvent: (MotionEvent, String) -> Unit) : View(context) {
    private val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
    private var fillColor = android.graphics.Color.DKGRAY
    private var labelColor = android.graphics.Color.WHITE
    init { isClickable = true; contentDescription = "鼠标按键接收测试区" }
    fun setColors(fill: Int, label: Int) { fillColor = fill; labelColor = label; invalidate() }
    override fun onDraw(canvas: AndroidCanvas) {
        paint.color = fillColor
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 12f * resources.displayMetrics.density, 12f * resources.displayMetrics.density, paint)
        paint.color = labelColor
        paint.textAlign = AndroidPaint.Align.CENTER
        paint.textSize = 18f * resources.displayMetrics.scaledDensity
        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText("A 主键 · B 副键", width / 2f, baseline, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        onEvent(event, "onTouchEvent")
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE) && event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
            onEvent(event, "onGenericMotionEvent")
            return true
        }
        return super.onGenericMotionEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

@Composable private fun StickIndicator(label: String, x: Float, y: Float) {
    val color = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(100.dp).padding(10.dp)) {
            val radius = size.minDimension / 2
            drawCircle(outline, radius, style = Stroke(2.dp.toPx()))
            drawLine(outline, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y))
            drawLine(outline, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius))
            drawCircle(color, 7.dp.toPx(), Offset(center.x + x * radius * .8f, center.y + y * radius * .8f))
        }
        Text(label)
        Text(String.format(Locale.ROOT, "X %+.2f  Y %+.2f", x, y), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}
