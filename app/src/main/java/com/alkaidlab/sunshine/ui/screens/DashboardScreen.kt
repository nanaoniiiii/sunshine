package com.alkaidlab.sunshine.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alkaidlab.sunshine.ui.theme.*
import com.alkaidlab.sunshine.ui.viewmodel.SunshineViewModel

@Composable
fun DashboardScreen(
    viewModel: SunshineViewModel,
    onRequestMediaProjection: () -> Unit,
    onRequestShizuku: () -> Unit,
    onRequestBatteryOptimization: () -> Unit
) {
    val isRunning by viewModel.isServiceRunning.collectAsState()
    val ip by viewModel.serviceIp.collectAsState()
    val vpnIp by viewModel.serviceVpnIp.collectAsState()
    val ipv6 by viewModel.serviceIpv6.collectAsState()
    val config by viewModel.config.collectAsState()
    val sessions by viewModel.runtimeSessions.collectAsState()
    val perf by viewModel.perf.collectAsState()
    val pinRequested by viewModel.pinRequested.collectAsState()

    val isMediaProjectionGranted by viewModel.isMediaProjectionGranted.collectAsState()
    val isShizukuGranted by viewModel.isShizukuGranted.collectAsState()
    val isAudioGranted by viewModel.isAudioRecordGranted.collectAsState()
    val isBatteryOptimized by viewModel.isBatteryOptimizedIgnored.collectAsState()

    var pinInput by remember { mutableStateOf("") }

    Column(
        modifier =渐变Modifier(
            Modifier
                .fillMaxSize()
                .background(DarkBg)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SUNSHINE",
                    color = PrimaryCyan,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )
                Text(
                    text = config?.version ?: "v2026.823.92130 (Android)",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Status Badge
            Surface(
                color = if (isRunning) AccentGreen.copy(alpha = 0.15f) else AccentOrange.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isRunning) AccentGreen else AccentOrange
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (isRunning) AccentGreen else AccentOrange,
                                shape = CircleShape
                            )
                    )
                    Text(
                        text = if (isRunning) "服务运行中" else "服务就绪",
                        color = if (isRunning) AccentGreen else AccentOrange,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Prominent PIN Alert if Moonlight requests pairing
        if (pinRequested != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = AccentOrange.copy(alpha = 0.1f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentOrange),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = AccentOrange)
                        Text("Moonlight 客户端请求配对", color = AccentOrange, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "客户端已发起配对，请输入 Moonlight 屏幕上显示的 4 位 PIN 码以完成连接：",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { if (it.length <= 4) pinInput = it },
                            placeholder = { Text("4位PIN码") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentOrange,
                                unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        Button(
                            onClick = {
                                if (pinInput.length == 4) {
                                    viewModel.submitPin(pinInput)
                                    pinInput = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("确定配对", color = DarkBg, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Server Information Card
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
                    text = "网络与服务节点",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                // LAN IPv4
                CopyableAddressRow(
                    title = "局域网 IPv4",
                    value = ip,
                    badgeText = "LAN",
                    badgeColor = PrimaryCyan,
                    isAvailable = ip != "127.0.0.1"
                )

                // Tailscale / VPN IP
                CopyableAddressRow(
                    title = "Tailscale / 虚拟内网",
                    value = vpnIp ?: "未开启 / 未检测到虚拟网卡",
                    badgeText = if (vpnIp != null) "VPN穿透" else null,
                    badgeColor = AccentOrange,
                    isAvailable = vpnIp != null
                )

                // Global Unicast IPv6
                val displayIpv6 = ipv6 ?: config?.ipv6Address
                CopyableAddressRow(
                    title = "全球单播 IPv6",
                    value = displayIpv6 ?: "未分配公网 IPv6",
                    badgeText = if (displayIpv6 != null) "公网直连推荐" else null,
                    badgeColor = AccentGreen,
                    isAvailable = displayIpv6 != null
                )

                Surface(
                    color = PrimaryCyan.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryCyan.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = PrimaryCyan, modifier = Modifier.size(14.dp))
                            Text("Moonlight 跨网直连与多端同屏说明", color = PrimaryCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "• 外网直连：蜂窝移动网络与大部分宽带分配有公网 IPv6，在 Moonlight 中输入上方 [IPv6]:47989 即可端到端低延迟直连，完全无需中继穿透。\n" +
                            "• 内网穿透：开启 Tailscale 后，可复制上方 100.x.x.x 地址在客户端中连接。\n" +
                            "• 多端同屏：支持多台 Moonlight 客户端同时连入观战与协同控制，共享手机硬件编码管道，零额外性能损耗。",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    InfoField(label = "推流端口", value = config?.port ?: "47989 (UDP/TCP)")
                    InfoField(label = "Web 管理端口", value = "${config?.webPort ?: "47990"} (REST API)")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    InfoField(label = "发现广播", value = "_nvstream._tcp (mDNS)")
                    InfoField(label = "协议栈族", value = if (config?.addressFamily == "both") "双栈 IPv4+IPv6" else "双栈自适应")
                }

                Divider(color = CardBorder, thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("硬件编码器", color = TextSecondary, fontSize = 12.sp)
                        Text(config?.encoder ?: "AMediaCodec (Hardware)", color = PrimaryCyan, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }

                    if (!isMediaProjectionGranted) {
                        Button(
                            onClick = onRequestMediaProjection,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ScreenShare, contentDescription = null, tint = DarkBg, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("授权录屏", color = DarkBg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Real-time Stream & Performance Card
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "实时推流监控",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )

                    Surface(
                        color = if (sessions.isNotEmpty()) AccentGreen.copy(alpha = 0.15f) else TextMuted.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (sessions.isNotEmpty()) "推流中 (${sessions.size})" else "空闲中",
                            color = if (sessions.isNotEmpty()) AccentGreen else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (sessions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "当前无活跃 Moonlight 客户端连接\n可在客户端添加 IP: $ip 开启串流",
                            color = TextMuted,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                } else {
                    sessions.forEach { session ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CardBorder.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(session.clientName, color = TextPrimary, fontWeight = FontWeight.Bold)
                                Text(session.codec, color = PrimaryCyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("画面规格: ${session.width}x${session.height} @ ${session.fps}fps", color = TextSecondary, fontSize = 12.sp)
                                Text("码率: ${session.bitrate / 1000} Mbps", color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            if (session.clientIp != "127.0.0.1") {
                                Text("连接节点: ${session.clientIp}", color = TextMuted, fontSize = 11.sp)
                            }
                        }
                    }

                    if (perf.active) {
                        Divider(color = CardBorder, thickness = 1.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            MetricItem("实时帧率", "${perf.fps} FPS")
                            MetricItem("硬编延迟", "${perf.encodeTimeMs} ms")
                            MetricItem("传输延迟", "${perf.networkLatencyMs} ms")
                        }
                    }
                }
            }
        }

        // System Permissions & Privileges Card
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
                    text = "系统权限与特权注入",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                PermissionRow(
                    icon = Icons.Default.ScreenShare,
                    title = "屏幕录制 (MediaProjection)",
                    desc = "用于系统虚拟屏幕创建与画面捕获",
                    granted = isMediaProjectionGranted,
                    actionText = if (!isMediaProjectionGranted) "授权" else null,
                    onAction = onRequestMediaProjection
                )

                PermissionRow(
                    icon = Icons.Default.TouchApp,
                    title = "Shizuku 特权注入",
                    desc = "免 Root 注入触控、鼠标移动与虚拟按键",
                    granted = isShizukuGranted,
                    actionText = if (!isShizukuGranted) "连接" else null,
                    onAction = onRequestShizuku
                )

                PermissionRow(
                    icon = Icons.Default.Mic,
                    title = "音频内录 (AudioPlaybackCapture)",
                    desc = "捕获手机媒体音频并编码推流",
                    granted = isAudioGranted,
                    actionText = null,
                    onAction = {}
                )

                PermissionRow(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "后台保活 (忽略电池优化)",
                    desc = "防止息屏推流时系统杀死进程",
                    granted = isBatteryOptimized,
                    actionText = if (!isBatteryOptimized) "设置" else null,
                    onAction = onRequestBatteryOptimization
                )
            }
        }
    }
}

private fun 渐变Modifier(modifier: Modifier): Modifier = modifier

@Composable
fun InfoField(label: String, value: String) {
    Column {
        Text(text = label, color = TextSecondary, fontSize = 11.sp)
        Text(text = value, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Text(value, color = PrimaryCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun PermissionRow(
    icon: ImageVector,
    title: String,
    desc: String,
    granted: Boolean,
    actionText: String?,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (granted) AccentGreen else TextMuted,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(desc, color = TextSecondary, fontSize = 11.sp)
            }
        }

        if (actionText != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(actionText, color = PrimaryCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = if (granted) AccentGreen else TextMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun CopyableAddressRow(
    title: String,
    value: String,
    badgeText: String? = null,
    badgeColor: Color = PrimaryCyan,
    isAvailable: Boolean = true
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Surface(
        color = CardBorder.copy(alpha = 0.35f),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = title, color = TextSecondary, fontSize = 11.sp)
                    if (badgeText != null) {
                        Surface(
                            color = badgeColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = badgeText,
                                color = badgeColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    color = if (isAvailable) TextPrimary else TextMuted,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (isAvailable) {
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(value))
                        Toast.makeText(context, "已复制 $title: $value", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        tint = PrimaryCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
