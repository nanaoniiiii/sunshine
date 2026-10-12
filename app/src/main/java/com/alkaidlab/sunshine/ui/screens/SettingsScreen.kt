package com.alkaidlab.sunshine.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alkaidlab.sunshine.ui.theme.*
import com.alkaidlab.sunshine.ui.viewmodel.SunshineViewModel

@Composable
fun SettingsScreen(
    viewModel: SunshineViewModel
) {
    val config by viewModel.config.collectAsState()

    var encoderH264 by remember { mutableStateOf("auto") }
    var encoderHevc by remember { mutableStateOf("auto") }
    var hevcEnabled by remember { mutableStateOf(true) }
    var autoRotate by remember { mutableStateOf(true) }
    var autoScale by remember { mutableStateOf(true) }
    var maxBitrateText by remember { mutableStateOf("50000") }
    var minBitrateText by remember { mutableStateOf("5000") }
    var portText by remember { mutableStateOf("47989") }
    var webPortText by remember { mutableStateOf("47990") }
    var maxFpsText by remember { mutableStateOf("240") }
    var audioBufferText by remember { mutableStateOf("40") }
    var repeatFrames by remember { mutableStateOf(true) }
    var wifiLowLatency by remember { mutableStateOf(true) }
    var addressFamily by remember { mutableStateOf("both") }

    LaunchedEffect(config) {
        config?.let { c ->
            encoderH264 = c.encoderH264
            encoderHevc = c.encoderHevc
            hevcEnabled = c.hevcMode != "disabled"
            autoRotate = c.autoRotate != "false"
            autoScale = c.autoScale != "false"
            maxBitrateText = c.maxBitrate
            minBitrateText = c.minBitrate
            portText = c.port
            webPortText = c.webPort
            addressFamily = c.addressFamily
            maxFpsText = c.maxStreamFps
            audioBufferText = c.audioBufferMs
            repeatFrames = c.repeatFrames != "false"
            wifiLowLatency = c.wifiLowLatency != "false"
        }
    }

    LaunchedEffect(Unit) {
        viewModel.fetchConfig()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "串流设置",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "更改后即时持久化到配置文件",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = {
                    val map = mapOf(
                        "encoder_h264" to encoderH264,
                        "encoder_hevc" to encoderHevc,
                        "hevc_mode" to if (hevcEnabled) "enabled" else "disabled",
                        "auto_rotate" to autoRotate.toString(),
                        "auto_scale" to autoScale.toString(),
                        "max_bitrate" to maxBitrateText,
                        "min_bitrate" to minBitrateText,
                        "port" to portText,
                        "web_port" to webPortText,
                        "address_family" to addressFamily,
                        "max_stream_fps" to maxFpsText,
                        "repeat_frames" to repeatFrames.toString(),
                        "audio_buffer_ms" to audioBufferText,
                        "wifi_low_latency" to wifiLowLatency.toString()
                    )
                    viewModel.saveConfig(map)
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, tint = DarkBg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("保存设置", color = DarkBg, fontWeight = FontWeight.Bold)
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("串流节奏与缓冲", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = maxFpsText, onValueChange = { maxFpsText = it },
                        label = { Text("帧率上限 (15–240)") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = audioBufferText, onValueChange = { audioBufferText = it },
                        label = { Text("音频缓冲 (20–100 ms)") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text("每个客户端按请求帧率独立送帧；缓冲较小可降低音频等待，但更容易受系统调度影响。", color = TextSecondary, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("静态画面持续补帧", color = TextPrimary, modifier = Modifier.weight(1f))
                    Switch(checked = repeatFrames, onCheckedChange = { repeatFrames = it })
                }
                Text("关闭后静止画面减少送帧；如客户端出现停顿或重连黑屏，请开启。", color = TextMuted, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("串流期间 Wi-Fi 低延迟模式", color = TextPrimary, modifier = Modifier.weight(1f))
                    Switch(checked = wifiLowLatency, onCheckedChange = { wifiLowLatency = it })
                }
                Text("仅有连接时保持处理器唤醒并请求 Wi-Fi 低延迟模式，可能增加耗电；效果取决于系统和无线网络。", color = TextSecondary, fontSize = 12.sp)
            }
        }

        // Section 1: Video & Hardware Encoder
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "视频编码与画面策略",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("HEVC / H.265", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("允许客户端请求 HEVC；具体编码器由下方选项决定", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = hevcEnabled,
                        onCheckedChange = { hevcEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DarkBg,
                            checkedTrackColor = PrimaryCyan,
                            uncheckedTrackColor = CardBorder
                        )
                    )
                }

                EncoderChoice("H.264 编码器", encoderH264, config?.encoderOptions.orEmpty().filter { it.mime == "video/avc" }) { encoderH264 = it }
                EncoderChoice("HEVC / H.265 编码器", encoderHevc, config?.encoderOptions.orEmpty().filter { it.mime == "video/hevc" }) { encoderHevc = it }
                Text("自动先使用系统默认编码器，启动失败后尝试其他编码器。指定编码器失败时会提示切换，不会自动替换。保存后在下次连接生效。", color = TextSecondary, fontSize = 12.sp)

                Divider(color = CardBorder, thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动屏幕旋转 (Auto Rotate)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("根据手机横竖屏状态自动同步 Moonlight 串流方向", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoRotate,
                        onCheckedChange = { autoRotate = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DarkBg,
                            checkedTrackColor = PrimaryCyan,
                            uncheckedTrackColor = CardBorder
                        )
                    )
                }

                Divider(color = CardBorder, thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动缩放分辨率 (Auto Scale)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("自动匹配客户端请求的宽高质量以避免黑边", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoScale,
                        onCheckedChange = { autoScale = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DarkBg,
                            checkedTrackColor = PrimaryCyan,
                            uncheckedTrackColor = CardBorder
                        )
                    )
                }
            }
        }

        // Section 2: Bitrate
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "码率限制 (Kbps)",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = minBitrateText,
                        onValueChange = { minBitrateText = it },
                        label = { Text("最小码率") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    OutlinedTextField(
                        value = maxBitrateText,
                        onValueChange = { maxBitrateText = it },
                        label = { Text("最大码率") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            }
        }

        // Section: Network & Address Family (Foundation Sunshine IPv6)
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "网络协议族与 IPv6 (Foundation Sunshine)",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("IPv4 + IPv6 双栈模式 (address_family = both)", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("同时监听 [::] 与 0.0.0.0，支持公网 IPv6 免 NAT 穿透与局域网双向串流", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = addressFamily == "both",
                        onCheckedChange = { addressFamily = if (it) "both" else "ipv4" },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DarkBg,
                            checkedTrackColor = PrimaryCyan,
                            uncheckedTrackColor = CardBorder
                        )
                    )
                }

                if (!config?.ipv6Address.isNullOrBlank()) {
                    Surface(
                        color = CardBorder.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "当前分配 IPv6: ${config?.ipv6Address}",
                            color = PrimaryCyan,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // Section 3: Ports & Service
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "端口与网络服务",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("GameStream 端口") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    OutlinedTextField(
                        value = webPortText,
                        onValueChange = { webPortText = it },
                        label = { Text("Web 管理端口") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }

                Text(
                    text = "注意: 更改端口后需要重启 Sunshine 服务方可生效",
                    color = TextMuted,
                    fontSize = 11.sp
                )

                Button(
                    onClick = { viewModel.restartServer() },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentOrange.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = AccentOrange)
                    Spacer(Modifier.width(6.dp))
                    Text("重启 Sunshine 服务", color = AccentOrange, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun EncoderChoice(label: String, selected: String, options: List<com.alkaidlab.sunshine.data.EncoderCapability>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = TextPrimary, fontSize = 14.sp)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (selected == "auto") "自动（系统默认优先）" else selected,
                    modifier = Modifier.weight(1f), color = TextPrimary)
                Icon(Icons.Default.ArrowDropDown, contentDescription = "选择编码器", tint = PrimaryCyan)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("自动（系统默认优先）") }, onClick = { onSelect("auto"); expanded = false })
                options.distinctBy { it.name }.forEach { option ->
                    DropdownMenuItem(text = { Column { Text(option.name); Text(if (option.hardware) "硬件编码器" else "软件编码器", fontSize = 11.sp) } },
                        onClick = { onSelect(option.name); expanded = false })
                }
            }
        }
        if (selected != "auto" && options.none { it.name == selected })
            Text("此设备当前未列出该编码器，请切换选项。", color = AccentOrange, fontSize = 12.sp)
    }
}
