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

    var hevcEnabled by remember { mutableStateOf(true) }
    var autoRotate by remember { mutableStateOf(true) }
    var autoScale by remember { mutableStateOf(true) }
    var maxBitrateText by remember { mutableStateOf("50000") }
    var minBitrateText by remember { mutableStateOf("5000") }
    var portText by remember { mutableStateOf("47989") }
    var webPortText by remember { mutableStateOf("47990") }
    var addressFamily by remember { mutableStateOf("both") }

    LaunchedEffect(config) {
        config?.let { c ->
            hevcEnabled = c.hevcMode != "disabled"
            autoRotate = c.autoRotate != "false"
            autoScale = c.autoScale != "false"
            maxBitrateText = c.maxBitrate
            minBitrateText = c.minBitrate
            portText = c.port
            webPortText = c.webPort
            addressFamily = c.addressFamily
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
            Column {
                Text(
                    text = "参数配置 (sunshine.conf)",
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
                        "hevc_mode" to if (hevcEnabled) "enabled" else "disabled",
                        "auto_rotate" to autoRotate.toString(),
                        "auto_scale" to autoScale.toString(),
                        "max_bitrate" to maxBitrateText,
                        "min_bitrate" to minBitrateText,
                        "port" to portText,
                        "web_port" to webPortText,
                        "address_family" to addressFamily
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
                        Text("HEVC / H.265 硬编", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("开启后优先使用 HEVC 硬件编码器，显著提升高动态画质", color = TextSecondary, fontSize = 11.sp)
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
