package com.alkaidlab.sunshine.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.alkaidlab.sunshine.BuildConfig
import com.alkaidlab.sunshine.ui.components.*
import com.alkaidlab.sunshine.ui.theme.*
import com.alkaidlab.sunshine.ui.viewmodel.SunshineViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(viewModel: SunshineViewModel, onRequestMediaProjection: () -> Unit,
    onRequestShizuku: () -> Unit, onRequestBatteryOptimization: () -> Unit,
    onOpenPairing: () -> Unit = {}, onToggleTheme: () -> Unit = {}, darkTheme: Boolean = false) {
    val running by viewModel.isServiceRunning.collectAsState()
    val ip by viewModel.serviceIp.collectAsState()
    val vpn by viewModel.serviceVpnIp.collectAsState()
    val ipv6 by viewModel.serviceIpv6.collectAsState()
    val config by viewModel.config.collectAsState()
    val sessions by viewModel.runtimeSessions.collectAsState()
    val fullscreenBusy by viewModel.fullscreenBusy.collectAsState()
    val virtualScreen by viewModel.virtualScreenState.collectAsState()
    val perf by viewModel.perf.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val capture by viewModel.isMediaProjectionGranted.collectAsState()
    val shizuku by viewModel.isShizukuGranted.collectAsState()
    val audio by viewModel.isAudioRecordGranted.collectAsState()
    val battery by viewModel.isBatteryOptimizedIgnored.collectAsState()
    var moreNetwork by rememberSaveable { mutableStateOf(false) }
    var moreMetrics by rememberSaveable { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding,
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageHeader("Sunshine", "将这台 Android 设备连接到 Moonlight") {
            FilledTonalIconButton(onClick = onToggleTheme, modifier = Modifier.size(48.dp)) {
                Icon(if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    if (darkTheme) "切换为浅色" else "切换为深色", tint = PrimaryCyan)
            }
        } }
        item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (sessions.isNotEmpty()) "正在串流" else if (running && capture) "准备好连接了" else "准备串流",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
                        StatusTag(if (running) "服务运行中" else "服务未运行",
                            if (running) AccentGreen else AccentOrange)
                    }
                    Text(if (sessions.isNotEmpty()) "${sessions.size} 个客户端已连接"
                        else if (capture) "在 Moonlight 中添加下方地址，然后输入配对码。"
                        else "先允许屏幕录制，再连接 Moonlight。",
                        color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyLarge)
                    if (!capture) PrimaryButton(onClick = onRequestMediaProjection, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.ScreenShare, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text("允许屏幕录制")
                    } else if (sessions.isNotEmpty()) {
                        PrimaryButton(onClick = viewModel::toggleVirtualScreen,
                            enabled = !virtualScreen.busy && !fullscreenBusy && (virtualScreen.active || shizuku),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Icon(if (virtualScreen.active) Icons.Default.Close else Icons.Default.DesktopWindows, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp)); Text(if (virtualScreen.busy) "切换显示中…" else if (virtualScreen.active) {
                                if (virtualScreen.mode == "screen_off") "恢复主屏" else "退出虚拟屏"
                            } else if (config?.virtualScreenMode == "screen_off") "主屏息屏串流" else "创建虚拟屏")
                        }
                        if (virtualScreen.active) Text(if (virtualScreen.mode == "screen_off") "主屏息屏串流 · 原主屏画面与操作"
                            else "虚拟屏 ${virtualScreen.width} × ${virtualScreen.height} · ${virtualScreen.launcher}",
                            color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                    } else PrimaryButton(onClick = onOpenPairing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.AddLink, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp)); Text("配对新设备")
                    }
                }
            }
        }
        item { SectionCard("连接地址", Icons.Default.Wifi) {
            AddressRow("局域网", ip.takeUnless { it == "127.0.0.1" }, "请连接 Wi-Fi 或以太网")
            TextButton(onClick = { moreNetwork = !moreNetwork }, contentPadding = PaddingValues(0.dp)) {
                Text(if (moreNetwork) "收起其他连接方式" else "其他连接方式")
                Icon(if (moreNetwork) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (moreNetwork) {
                AddressRow("VPN / Tailscale", vpn, "未检测到虚拟网卡")
                AddressRow("IPv6", ipv6 ?: config?.ipv6Address, "未分配公网 IPv6")
                Text("跨网连接需要双方网络可达，并允许相应端口通过防火墙。也可使用 Tailscale 地址连接。",
                    color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Text("串流端口 ${config?.port ?: "47989"} · 管理端口 ${config?.webPort ?: "47990"}",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        } }
        item { SectionCard("串流状态", Icons.Default.ShowChart) {
            if (sessions.isEmpty()) {
                Text("等待客户端连接", style = MaterialTheme.typography.titleMedium)
                Text("连接后显示画面规格、送帧率和延迟数据。", color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                sessions.forEach { session ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(session.clientName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            StatusTag(session.codec)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${session.width} × ${session.height} · ${session.fps} FPS",
                                color = TextSecondary, modifier = Modifier.weight(1f))
                            FilledTonalButton(
                                onClick = { session.sessionId?.let(viewModel::fullscreenSession) },
                                enabled = !fullscreenBusy && (!virtualScreen.active || virtualScreen.mode == "screen_off") && !virtualScreen.busy && session.sessionId != null,
                                modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Default.Fullscreen, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("全屏")
                            }
                        }
                        val target = diagnostics?.frames?.targets?.firstOrNull { it.threadId == session.videoThreadId }
                        val native = diagnostics?.video?.firstOrNull { it.threadId == session.videoThreadId }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("送帧 ${target?.let { "%.1f".format(it.measuredFps) } ?: "—"} FPS", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            Text("GPU送帧 ${target?.gpuHandoffMs?.let { "%.2f".format(it) } ?: "—"} ms", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            Text("RTT ${native?.controlRttMs?.toString() ?: "—"} ms", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            Text("请求 ${native?.requestedBitrateKbps?.takeIf { it > 0 }?.let { "%.1f".format(it / 1000.0) } ?: "—"} Mbps", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        session.appName?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall) }

                    }
                }
                TextButton(onClick = { moreMetrics = !moreMetrics }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (moreMetrics) "收起诊断数据" else "查看诊断数据")
                    Icon(if (moreMetrics) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
                if (moreMetrics) diagnostics?.let { d ->
                    Text("各路 RTT 来自各自的串流控制通道，不包含画面捕获、编码和显示耗时。",
                        color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    Text("ICMP 探测 RTT：" + (perf.networkLatencyMs?.let { "%.1f ms".format(it) }
                        ?: if (perf.networkProbeIp == null) "未取得可用的客户端地址或回应" else "未取得新回应，样本已过期"),
                        color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    d.frames.targets.forEachIndexed { index, target ->
                        Text("第 ${index + 1} 路 · ${target.width} × ${target.height} · 实际送帧 ${"%.1f".format(target.measuredFps)} FPS · 上限 ${target.effectiveFps} FPS",
                            color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("各路送帧合计 ${"%.1f".format(d.frames.targets.sumOf { it.measuredFps })} 帧/秒",
                        color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    Text("GPU P95 ${"%.2f".format(d.frames.handoffP95Ms)} ms · 合并帧 ${d.frames.coalescedFrames}",
                        color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    Text("音频排队 ${"%.1f".format(d.audio.queuedMs)} ms · 总发送 ${"%.1f".format(d.perf.bitrateKbps / 1000.0)} Mbps",
                        color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    d.video.forEach { v ->
                        Text(v.encoder, style = MaterialTheme.typography.bodyMedium)
                        Text("发送队列 ${v.queueFrames} 帧 · 丢弃 ${v.queueDroppedFrames} 帧 · FEC ${v.fecPercentage}%",
                            color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        Text("编码→输出 P95 ${v.submitToOutputP95Ms?.let { "%.1f".format(it) } ?: "—"} ms · 发送 P95 ${v.frameSendP95Ms?.let { "%.1f".format(it) } ?: "—"} ms",
                            color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } }
        item { SectionCard("设备准备", Icons.Default.VerifiedUser) {
            PermissionRow("屏幕录制", "捕获并传输设备画面", capture, onRequestMediaProjection)
            Divider(color = CardBorder)
            PermissionRow("远程控制", "连接 Shizuku 后支持触控与按键", shizuku, onRequestShizuku)
            Divider(color = CardBorder)
            PermissionRow("后台运行", "允许 Sunshine 忽略电池优化", battery, onRequestBatteryOptimization)
            Divider(color = CardBorder)
            PermissionRow("音频录制", "用于传输应用媒体声音", audio, null)
        } }
        item { Text(BuildConfig.VERSION_NAME, color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AddressRow(title: String, value: String?, unavailable: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1800); copied = false } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text(value ?: unavailable, style = MaterialTheme.typography.titleMedium,
                fontFamily = if (value != null) FontFamily.Monospace else FontFamily.Default)
        }
        if (value != null) IconButton(onClick = { clipboard.setText(AnnotatedString(value)); copied = true }) {
            Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                if (copied) "已复制$title 地址" else "复制$title 地址", tint = PrimaryCyan)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
        Text(unit, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PermissionRow(title: String, description: String, granted: Boolean, onAction: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (granted) Icons.Default.CheckCircleOutline else Icons.Default.RadioButtonUnchecked,
            null, tint = if (granted) AccentGreen else AccentOrange, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        if (granted) Text("已就绪", color = AccentGreen, style = MaterialTheme.typography.labelMedium)
        else if (onAction != null) TextButton(onClick = onAction) { Text("设置") }
        else Text("未授权", color = AccentOrange, style = MaterialTheme.typography.labelMedium)
    }
}
