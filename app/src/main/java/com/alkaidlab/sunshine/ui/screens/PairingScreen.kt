package com.alkaidlab.sunshine.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.alkaidlab.sunshine.data.ClientItem
import com.alkaidlab.sunshine.ui.components.*
import com.alkaidlab.sunshine.ui.theme.*
import com.alkaidlab.sunshine.ui.viewmodel.SunshineViewModel

@Composable
fun PairingScreen(viewModel: SunshineViewModel) {
    val clients by viewModel.clients.collectAsState()
    var pin by rememberSaveable { mutableStateOf("") }
    var rename by remember { mutableStateOf<ClientItem?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var unpair by remember { mutableStateOf<ClientItem?>(null) }
    var unpairAll by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    fun submit() { if (pin.length == 4) { viewModel.submitPin(pin); pin = "" } }
    LaunchedEffect(Unit) { viewModel.fetchClients() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding,
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageHeader("设备", "已配对 ${clients.size} 台设备") {
            IconButton(onClick = { viewModel.fetchAll() }) { Icon(Icons.Default.Refresh, "刷新设备") }
        } }
        item { SectionCard("配对新设备", Icons.Default.AddLink) {
            Text("在 Moonlight 中点击这台设备，将客户端显示的 4 位数字填入下方。",
                color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            PinField(pin, { pin = it }, Modifier.fillMaxWidth(), ::submit)
            PrimaryButton(onClick = ::submit, enabled = pin.length == 4, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("确认配对")
            }
        } }
        item { Text("已配对设备", style = MaterialTheme.typography.titleMedium) }
        if (clients.isEmpty()) item { SectionCard {
            EmptyState(Icons.Default.Devices, "还没有已配对的设备", "在 Moonlight 添加首页的连接地址即可开始配对。")
        } }
        items(clients, key = { it.uuid }) { client ->
            var details by rememberSaveable(client.uuid) { mutableStateOf(false) }
            SectionCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Devices, null, tint = PrimaryCyan, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(client.name, style = MaterialTheme.typography.titleMedium)
                        Text("设备标识 · ${client.uuid.take(8)}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    if (client.active) StatusTag("推流中", AccentGreen)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { rename = client; name = client.name }) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("改名")
                    }
                    TextButton(onClick = { details = !details }) { Text(if (details) "收起" else "详情") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { unpair = client }) { Icon(Icons.Default.LinkOff, "解除 ${client.name} 的配对", tint = AccentRed) }
                }
                if (details) {
                    Text(client.uuid, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(client.uuid)) }) { Text("复制设备标识") }
                }
            }
        }
        if (clients.isNotEmpty()) item {
            TextButton(onClick = { unpairAll = true }, colors = ButtonDefaults.textButtonColors(contentColor = AccentRed)) {
                Text("解除所有设备配对")
            }
        }
    }
    rename?.let { target ->
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("设备名称") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { viewModel.renameClient(target.uuid, name.trim()); rename = null }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } })
    }
    if (unpair != null || unpairAll) AlertDialog(onDismissRequest = { unpair = null; unpairAll = false },
        title = { Text(if (unpairAll) "解除所有设备配对？" else "解除 ${unpair?.name} 的配对？") },
        text = { Text("解除后需要重新输入配对码才能再次连接。") },
        confirmButton = { TextButton(onClick = {
            if (unpairAll) viewModel.unpairAll() else unpair?.let { viewModel.unpairClient(it.uuid) }
            unpair = null; unpairAll = false
        }) { Text("解除配对", color = AccentRed) } },
        dismissButton = { TextButton(onClick = { unpair = null; unpairAll = false }) { Text("取消") } })
}
