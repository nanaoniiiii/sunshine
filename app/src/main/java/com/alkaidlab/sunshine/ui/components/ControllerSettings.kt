package com.alkaidlab.sunshine.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.provider.Settings
import com.alkaidlab.sunshine.controller.*
import com.alkaidlab.sunshine.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun ControllerSettings(onTest: () -> Unit) {
    val context = LocalContext.current
    ControllerAdapter.initialize(context)
    val status by ControllerAdapter.status.collectAsState()
    val captured by ControllerAdapter.captured.collectAsState()
    val revision by ControllerAdapter.settingsRevision.collectAsState()
    val enabled = remember(revision) { ControllerAdapter.enabled() }
    val bindings = remember(revision) { ControllerAdapter.bindings() }
    var selected by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(15) }
    SectionCard("手柄与遥控适配器", Icons.Default.SportsEsports) {
        SettingSwitch("全局适配器", "随 Sunshine 服务启动，未连接串流也可操作手机", enabled, true, ControllerAdapter::setEnabled)
        Text(status.detail, color = TextSecondary)
        FilledTonalButton(onClick = onTest) { Text("打开手柄测试页面") }
        Text(if (status.systemNavigation) "系统控件层级导航已连接" else "系统控件层级导航未连接", color = TextSecondary)
        if (!status.systemNavigation) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("打开无障碍设置") }
        Text("LB / RB 立即切换当前位置的上级菜单。LT 按下打开快捷操作圆环，RT 按下打开常用应用圆环；按住保持，用任一摇杆选择，松开扳机触发，居中松开取消。游戏原生模式保留游戏扳机。", color = TextSecondary)
        Text("常用应用来源：${status.frequentAppsSource}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text("手机直连与 Moonlight 接收端均可使用。A 确定、B 返回、X 最近任务、Y 下拉栏；方向键 / 左摇杆移动焦点，右摇杆滑动。L3 双向切换鼠标模式，R3 双向切换游戏原生模式；回主页恢复系统映射。进入应用和游戏后仍保持当前模式。",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text("鼠标模式：左摇杆移动光标，A 左键、B 右键，支持按住、长按和拖动；X、Y 继续执行系统操作。游戏原生模式仍保留 R3 切回与主页键；补充快捷键为 Start＋B 返回、Start＋X 最近任务、Start＋Y 下拉栏。长按 Start＋Select 约一秒恢复导航并回主页。返回遵循系统规则，主页和最近任务可离开拦截返回的应用。",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        if (!status.running) Text("启动首页的 Sunshine 服务后生效。", color = TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in ControllerMode.values()) OutlinedButton(onClick = { ControllerAdapter.mode(mode) }, enabled = status.ready) { Text(mode.title) }
        }
        status.devices.forEach { device ->
            SettingSwitch(device.name, "${if (device.kind == "gamepad") "本机手柄" else "本机遥控器"} · ${status.modes[device.id]?.title ?: "待接管"}${if (device.grabbed) " · 已接管" else ""}",
                device.enabled, status.ready) { ControllerAdapter.deviceEnabled(device, it) }
        }
        val remote = status.modes.filterKeys { it.startsWith("remote:") }
        remote.forEach { (id, mode) -> Text("Moonlight 手柄 ${id.substringAfter(':').toIntOrNull()?.plus(1) ?: ""} · ${mode.title}") }
        if (status.devices.isEmpty() && remote.isEmpty()) Text("未检测到手柄或专用方向遥控器，连接后自动接管。", color = TextSecondary)
        Text("自定义按键", style = MaterialTheme.typography.titleMedium)
        val tokens = (ControllerButtons.tokens.values + listOf("POWER") + bindings.keys).distinct()
        tokens.forEach { token ->
            Row(Modifier.fillMaxWidth().clickable { selected = token }.padding(vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(token); Text(ControllerButtons.action(token, bindings).title, color = MaterialTheme.colorScheme.primary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { seconds = 15; recording = true; ControllerAdapter.capture() }, enabled = status.ready) { Text("录入其他按键") }
            TextButton(onClick = ControllerAdapter::resetBindings) { Text("恢复默认绑定") }
        }
        Text("系统导航保留组合键固定有效，防止自定义后无法返回。遥控器电源键打开系统电源菜单，由系统确认关机。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
    if (recording) {
        LaunchedEffect(captured) {
            if (captured != null) {
                if (!captured!!.startsWith("SYS_") && captured != "ESCAPE") selected = captured
                recording = false
            }
        }
        LaunchedEffect(Unit) {
            while (seconds > 0) { delay(1000); seconds-- }
            recording = false; ControllerAdapter.stopCapture()
        }
        DisposableEffect(Unit) { onDispose { ControllerAdapter.stopCapture() } }
        AlertDialog(onDismissRequest = { recording = false }, title = { Text("按下要绑定的按键") },
            text = { Text("支持本机手柄、遥控器和 Moonlight 手柄。等待 $seconds 秒，当前按键只用于录入。") },
            confirmButton = { TextButton(onClick = { recording = false }) { Text("取消") } })
    }
    selected?.let { token ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("绑定 $token") },
            text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                ControllerAction.values().forEach { action ->
                    Row(Modifier.fillMaxWidth().clickable { ControllerAdapter.bind(token, action); selected = null }.padding(vertical = 6.dp)) {
                        RadioButton(selected = ControllerButtons.action(token, bindings) == action, onClick = { ControllerAdapter.bind(token, action); selected = null })
                        Text(action.title, Modifier.padding(top = 12.dp))
                    }
                }
            } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("取消") } })
    }
}
