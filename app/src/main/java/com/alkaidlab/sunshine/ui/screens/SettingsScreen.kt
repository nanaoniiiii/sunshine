package com.alkaidlab.sunshine.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.alkaidlab.sunshine.data.EncoderCapability
import com.alkaidlab.sunshine.ui.components.*
import com.alkaidlab.sunshine.ui.theme.*
import com.alkaidlab.sunshine.ui.viewmodel.SunshineViewModel

@Composable
fun SettingsScreen(viewModel: SunshineViewModel, onOpenLogs: () -> Unit = {}, onFollowSystemTheme: () -> Unit = {},
    onOpenFaq: () -> Unit = {}, onOpenExperimental: () -> Unit = {}) {
    val config by viewModel.config.collectAsState()
    val draft by viewModel.settingsDraft.collectAsState()
    val saving by viewModel.configSaving.collectAsState()
    val saveError by viewModel.settingsSaveError.collectAsState()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var restart by remember { mutableStateOf(false) }
    val current = draft
    val errors = current?.errors().orEmpty()
    val focusManager = LocalFocusManager.current
    var focusedInputBounds by remember { mutableStateOf<Rect?>(null) }
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }
    fun edit(key: String, value: String) = viewModel.editSetting(key, value)
    @Composable fun number(label: String, hint: String, key: String) {
        NumberSetting(label, hint, current?.get(key).orEmpty(), errors[key], onChange = { edit(key, it) },
            onCommit = { viewModel.finishSettingEdit(key) }, onFocusBounds = { focusedInputBounds = it })
    }

    Column(Modifier.fillMaxSize().onGloballyPositioned { pageOrigin = it.boundsInRoot().topLeft }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                focusedInputBounds?.let { bounds ->
                    if (!bounds.contains(down.position + pageOrigin)) focusManager.clearFocus()
                }
            }
        }) {
        LazyColumn(Modifier.weight(1f), contentPadding = PagePadding,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { PageHeader("设置", "调整画面、编码器与连接策略") }
            if (saveError != null) item { SectionCard("设置未保存", Icons.Default.ErrorOutline) {
                Text(saveError.orEmpty(), color = AccentRed, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { viewModel.retrySettingsSave() }, enabled = !saving) { Text("重试") }
            } }
            if (current == null) item { SectionCard {
                Text("正在读取设备设置…", color = TextSecondary)
                TextButton(onClick = { viewModel.fetchAll() }) { Text("重新读取") }
            } } else {
                item { SectionCard("视频编码", Icons.Default.Memory) {
                    EncoderChoice("H.264 编码器", current["encoder_h264"], config?.encoderOptions.orEmpty().filter { it.mime == "video/avc" }, true) { edit("encoder_h264", it) }
                    EncoderChoice("H.265 / HEVC 编码器", current["encoder_hevc"], config?.encoderOptions.orEmpty().filter { it.mime == "video/hevc" }, true) { edit("encoder_hevc", it) }
                    Text("自动：优先使用系统默认编码器，启动失败后尝试其他编码器。指定编码器失败时提示切换。",
                        style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    SettingSwitch("允许 HEVC", "客户端可以请求 H.265 编码", current["hevc_mode"] != "disabled") { edit("hevc_mode", if (it) "enabled" else "disabled") }
                } }
                item { SectionCard("画面与流畅度", Icons.Default.SlowMotionVideo) {
                    number("帧率上限", "FPS · 15–240", "max_stream_fps")
                    SettingSwitch("自动旋转", "串流画布跟随设备横竖屏切换", current.checked("auto_rotate"), true) { edit("auto_rotate", it.toString()) }
                    SettingSwitch("固定画布旋转兼容", "手机接收端：关闭自由旋转，用竖屏分辨率连接，再随画面转动手机", current["rotation_compat"] == "true", true) { edit("rotation_compat", it.toString()) }
                    SettingSwitch("自动缩放", "匹配客户端请求的分辨率", current.checked("auto_scale"), true) { edit("auto_scale", it.toString()) }
                    SettingSwitch("静态画面持续补帧", "关闭可减少静止画面的送帧；遇到停顿时请开启", current.checked("repeat_frames"), true) { edit("repeat_frames", it.toString()) }
                } }
                item { SectionCard("虚拟屏", Icons.Default.DesktopWindows) {
                    val mode = current["virtual_screen_mode"]
                    SettingSwitch("第二屏桌面模式", "默认关闭：开启虚拟屏只关闭手机面板，继续显示主机内容；打开此项才使用独立桌面", mode == "desktop", true) {
                        edit("virtual_screen_mode", if (it) "desktop" else "screen_off")
                    }
                    if (mode == "desktop") {
                        val systemDesktop = current["virtual_screen_desktop"] == "system"
                        Column(Modifier.fillMaxWidth().padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("桌面模式选项", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                RadioButton(selected = !systemDesktop, onClick = { edit("virtual_screen_desktop", "sunshine") })
                                Text("全屏 Sunshine 桌面（默认）")
                            }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                RadioButton(selected = systemDesktop, onClick = { edit("virtual_screen_desktop", "system") })
                                Text("设备自带桌面（DeX 等）")
                            }
                            Text(if (systemDesktop) "使用三星 DeX 或本机提供的系统次级桌面，保留系统主页和任务栏。设备必须提供对应能力。"
                                else "Sunshine 临时作为虚拟屏主页，返回和主页键不会退出。“退出桌面”返回手机主屏，保留虚拟屏与全屏息屏串流。",
                                color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                            number("虚拟屏显示密度", "DPI · 120–640", "virtual_screen_density")
                        }
                    } else Text("主屏息屏串流：Moonlight 显示手机原有主页与应用，只关闭手机物理屏幕面板。", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    Text("配置在下次点击首页“开启虚拟屏”时应用。第二屏按 Moonlight 请求的分辨率创建；最后一个连接结束时恢复显示。", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                } }
                item { SectionCard("码率与缓冲", Icons.Default.Speed) {
                    number("最小码率", "Kbps · 1000–150000", "min_bitrate")
                    number("最大码率", "Kbps · 1000–150000", "max_bitrate")
                    number("音频缓冲", "ms · 20–100", "audio_buffer_ms")
                    SettingSwitch("Wi-Fi 低延迟模式", "连接期间请求低延迟模式，可能增加耗电", current.checked("wifi_low_latency"), true) { edit("wifi_low_latency", it.toString()) }
                } }
                item { SectionCard("网络与服务", Icons.Default.SettingsEthernet) {
                    SettingSwitch("IPv4 + IPv6 双栈", "允许通过可达的 IPv6 地址连接", current["address_family"] == "both", true) { edit("address_family", if (it) "both" else "ipv4") }
                    TextButton(onClick = { advanced = !advanced }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (advanced) "收起高级选项" else "高级选项")
                        Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                    if (advanced) {
                        number("串流端口", "更改后需要重启服务", "port")
                        number("管理端口", "更改后需要重启服务", "web_port")
                        OutlinedButton(onClick = { restart = true }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp)); Text("重启服务")
                        }
                    }
                } }
                item { SectionCard("外观", Icons.Default.Palette) {
                    TextButton(onClick = onFollowSystemTheme) { Text("恢复跟随系统外观") }
                } }
            }
                item { SectionCard("维护", Icons.Default.Build) {
                    TextButton(onClick = onOpenFaq, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.HelpOutline, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp)); Text("常见问题", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                        Icon(Icons.Default.ChevronRight, null)
                    }
                    TextButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Terminal, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp)); Text("运行日志", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                        Icon(Icons.Default.ChevronRight, null)
                    }
                } }
                item { AboutSection() }
                item { AppUpdateCard() }
                item { SectionCard("实验性功能", Icons.Default.Science) {
                    TextButton(onClick = onOpenExperimental, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Science, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp)); Text("实验性功能", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                        Icon(Icons.Default.ChevronRight, null)
                    }
                } }
        }
    }
    if (restart) AlertDialog(onDismissRequest = { restart = false }, title = { Text("重启 Sunshine 服务？") },
        text = { Text("正在连接的客户端会断开。重启后应用当前设置。") },
        confirmButton = { TextButton(onClick = { viewModel.restartServer(); restart = false }) { Text("重启") } },
        dismissButton = { TextButton(onClick = { restart = false }) { Text("取消") } })
}

@Composable
private fun NumberSetting(label: String, hint: String, value: String, error: String?, onChange: (String) -> Unit,
    onCommit: () -> Unit, onFocusBounds: (Rect?) -> Unit) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    DisposableEffect(Unit) {
        onDispose { if (focused) { onFocusBounds(null); onCommit() } }
    }
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth()
        .onGloballyPositioned { bounds = it.boundsInRoot(); if (focused) onFocusBounds(bounds) }
        .onFocusChanged { state ->
            if (state.isFocused) { focused = true; onFocusBounds(bounds) }
            else if (focused) { focused = false; onFocusBounds(null); onCommit() }
        }, label = { Text(label) },
        singleLine = true, isError = error != null,
        supportingText = { Text(error ?: hint) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        shape = MaterialTheme.shapes.small)
}

@Composable
private fun EncoderChoice(label: String, selected: String, options: List<EncoderCapability>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                Text(if (selected == "auto") "自动 · 系统默认优先" else selected, modifier = Modifier.weight(1f),
                    color = if (enabled) TextPrimary else TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(max = 360.dp)) {
                DropdownMenuItem(text = { Text("自动 · 系统默认优先") }, onClick = { onSelect("auto"); expanded = false })
                options.distinctBy { it.name }.forEach { option ->
                    DropdownMenuItem(text = { Column { Text(option.name, style = MaterialTheme.typography.bodyMedium)
                        Text(if (option.hardware) "硬件编码器" else "软件编码器", color = TextSecondary, style = MaterialTheme.typography.bodySmall) } },
                        onClick = { onSelect(option.name); expanded = false })
                }
            }
        }
        if (selected != "auto" && options.none { it.name == selected })
            Text("此设备未列出该编码器，请切换选项。", color = AccentOrange, style = MaterialTheme.typography.bodyMedium)
    }
}
