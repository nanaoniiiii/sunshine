package com.alkaidlab.sunshine.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.alkaidlab.sunshine.data.EncoderCapability
import com.alkaidlab.sunshine.ui.components.*
import com.alkaidlab.sunshine.ui.theme.*
import com.alkaidlab.sunshine.ui.viewmodel.*

@Composable
fun SettingsScreen(viewModel: SunshineViewModel, onOpenLogs: () -> Unit = {}, onFollowSystemTheme: () -> Unit = {}) {
    val config by viewModel.config.collectAsState()
    val draft by viewModel.settingsDraft.collectAsState()
    val saving by viewModel.configSaving.collectAsState()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var restart by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val current = draft
    val errors = current?.errors().orEmpty()
    val dirty = current != null && config != null && current != SettingsDraft.from(config!!)
    fun edit(key: String, value: String) = viewModel.editSetting(key, value)

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PagePadding,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { PageHeader("设置", "调整画面、编码器与连接策略") }
            if (current == null) item { SectionCard {
                Text("正在读取设备设置…", color = TextSecondary)
                TextButton(onClick = { viewModel.fetchAll() }) { Text("重新读取") }
            } } else {
                item { SectionCard("视频编码", Icons.Default.Memory) {
                    EncoderChoice("H.264 编码器", current["encoder_h264"], config?.encoderOptions.orEmpty().filter { it.mime == "video/avc" }, !saving) { edit("encoder_h264", it) }
                    EncoderChoice("H.265 / HEVC 编码器", current["encoder_hevc"], config?.encoderOptions.orEmpty().filter { it.mime == "video/hevc" }, !saving) { edit("encoder_hevc", it) }
                    Text("自动：优先使用系统默认编码器，启动失败后尝试其他编码器。指定编码器失败时提示切换。",
                        style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    SettingSwitch("允许 HEVC", "客户端可以请求 H.265 编码", current["hevc_mode"] != "disabled", !saving) { edit("hevc_mode", if (it) "enabled" else "disabled") }
                } }
                item { SectionCard("画面与流畅度", Icons.Default.SlowMotionVideo) {
                    NumberSetting("帧率上限", "FPS · 15–240", current["max_stream_fps"], errors["max_stream_fps"], !saving) { edit("max_stream_fps", it) }
                    SettingSwitch("自动旋转", "跟随设备横竖屏方向", current.checked("auto_rotate"), !saving) { edit("auto_rotate", it.toString()) }
                    SettingSwitch("自动缩放", "匹配客户端请求的分辨率", current.checked("auto_scale"), !saving) { edit("auto_scale", it.toString()) }
                    SettingSwitch("静态画面持续补帧", "关闭可减少静止画面的送帧；遇到停顿时请开启", current.checked("repeat_frames"), !saving) { edit("repeat_frames", it.toString()) }
                } }
                item { SectionCard("码率与缓冲", Icons.Default.Speed) {
                    NumberSetting("最小码率", "Kbps · 1000–150000", current["min_bitrate"], errors["min_bitrate"], !saving) { edit("min_bitrate", it) }
                    NumberSetting("最大码率", "Kbps · 1000–150000", current["max_bitrate"], errors["max_bitrate"], !saving) { edit("max_bitrate", it) }
                    NumberSetting("音频缓冲", "ms · 20–100", current["audio_buffer_ms"], errors["audio_buffer_ms"], !saving) { edit("audio_buffer_ms", it) }
                    SettingSwitch("Wi-Fi 低延迟模式", "连接期间请求低延迟模式，可能增加耗电", current.checked("wifi_low_latency"), !saving) { edit("wifi_low_latency", it.toString()) }
                } }
                item { SectionCard("网络与服务", Icons.Default.SettingsEthernet) {
                    SettingSwitch("IPv4 + IPv6 双栈", "允许通过可达的 IPv6 地址连接", current["address_family"] == "both", !saving) { edit("address_family", if (it) "both" else "ipv4") }
                    TextButton(onClick = { advanced = !advanced }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (advanced) "收起高级选项" else "高级选项")
                        Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                    if (advanced) {
                        NumberSetting("串流端口", "更改后需要重启服务", current["port"], errors["port"], !saving) { edit("port", it) }
                        NumberSetting("管理端口", "更改后需要重启服务", current["web_port"], errors["web_port"], !saving) { edit("web_port", it) }
                        OutlinedButton(onClick = { restart = true }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp)); Text("重启服务")
                        }
                    }
                } }
                item { SectionCard("外观", Icons.Default.Palette) {
                    TextButton(onClick = onFollowSystemTheme) { Text("恢复跟随系统外观") }
                } }
                item { SectionCard("维护", Icons.Default.Build) {
                    TextButton(onClick = onOpenLogs, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Terminal, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp)); Text("运行日志", modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                        Icon(Icons.Default.ChevronRight, null)
                    }
                } }
            }
        }
        Surface(color = CardBg, tonalElevation = 1.dp, shadowElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (saving) "正在保存…" else if (dirty) "有未保存的更改" else "已与设备设置同步",
                    color = if (dirty) AccentOrange else TextSecondary, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { discard = true }, enabled = dirty && !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("重置") }
                    PrimaryButton(onClick = { current?.let { viewModel.saveConfig(it.values) } },
                        enabled = dirty && errors.isEmpty() && !saving, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text("保存更改")
                    }
                }
                if (errors.isNotEmpty()) Text("请先修正上方的输入错误", color = AccentRed, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的更改？") },
        text = { Text("将恢复为设备当前保存的设置。") },
        confirmButton = { TextButton(onClick = { viewModel.discardSettingsDraft(); discard = false }) { Text("放弃更改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    if (restart) AlertDialog(onDismissRequest = { restart = false }, title = { Text("重启 Sunshine 服务？") },
        text = { Text("正在连接的客户端会断开。请先保存需要生效的设置。") },
        confirmButton = { TextButton(onClick = { viewModel.restartServer(); restart = false }) { Text("重启") } },
        dismissButton = { TextButton(onClick = { restart = false }) { Text("取消") } })
}

@Composable
private fun NumberSetting(label: String, hint: String, value: String, error: String?, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) },
        singleLine = true, enabled = enabled, isError = error != null,
        supportingText = { Text(error ?: hint) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
