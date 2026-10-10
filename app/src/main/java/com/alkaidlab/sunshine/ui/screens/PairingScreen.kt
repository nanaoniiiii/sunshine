package com.alkaidlab.sunshine.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alkaidlab.sunshine.data.ClientItem
import com.alkaidlab.sunshine.ui.theme.*
import com.alkaidlab.sunshine.ui.viewmodel.SunshineViewModel

@Composable
fun PairingScreen(
    viewModel: SunshineViewModel
) {
    val clients by viewModel.clients.collectAsState()
    var pinText by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ClientItem?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var showUnpairAllConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.fetchClients()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp),
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
                    text = "设备配对与管理",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "已配对设备: ${clients.size} 台",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            if (clients.isNotEmpty()) {
                TextButton(
                    onClick = { showUnpairAllConfirm = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentRed)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("全部解绑", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Quick PIN input Card
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
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
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = PrimaryCyan)
                    Text("输入配对码 (PIN)", color = TextPrimary, fontWeight = FontWeight.Bold)
                }

                Text(
                    "在 Moonlight 客户端点击本机后，客户端屏幕会显示 4 位数字配对码，在此输入即可完成配对：",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { if (it.length <= 4) pinText = it },
                        placeholder = { Text("4 位 PIN 码") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Button(
                        onClick = {
                            if (pinText.isNotBlank()) {
                                viewModel.submitPin(pinText)
                                pinText = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("配对", color = DarkBg, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Paired Clients List
        Text(
            text = "已授权客户端 (源自 sunshine_state.json)",
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )

        if (clients.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.DevicesOther,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Text("暂无已配对的客户端", color = TextMuted, fontSize = 14.sp)
                    Text("在电脑/平板的 Moonlight 输入本机 IP 即可发起配对", color = TextMuted, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(clients, key = { it.uuid }) { client ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = client.name,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    if (client.active) {
                                        Surface(
                                            color = AccentGreen.copy(alpha = 0.2f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "推流中",
                                                color = AccentGreen,
                                                fontSize = 10.sp,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "UUID: ${client.uuid}",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(onClick = {
                                    renameTarget = client
                                    renameInput = client.name
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "改名", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = {
                                    viewModel.unpairClient(client.uuid)
                                }) {
                                    Icon(Icons.Default.LinkOff, contentDescription = "解除配对", tint = AccentRed, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Rename Dialog
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名设备", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("设备名称") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryCyan,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameInput.isNotBlank()) {
                        viewModel.renameClient(target.uuid, renameInput)
                    }
                    renameTarget = null
                }) {
                    Text("保存", color = PrimaryCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = CardBg
        )
    }

    // Unpair All Confirmation Dialog
    if (showUnpairAllConfirm) {
        AlertDialog(
            onDismissRequest = { showUnpairAllConfirm = false },
            title = { Text("确认全部解绑？", color = TextPrimary) },
            text = { Text("此操作将清除所有已授权 Moonlight 客户端证书，客户端下次连接需要重新输入 PIN 码配对。", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unpairAll()
                    showUnpairAllConfirm = false
                }) {
                    Text("全部解绑", color = AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairAllConfirm = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = CardBg
        )
    }
}
