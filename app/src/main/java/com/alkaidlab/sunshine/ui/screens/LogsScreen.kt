package com.alkaidlab.sunshine.ui.screens

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alkaidlab.sunshine.service.SunshineLogManager
import com.alkaidlab.sunshine.ui.components.*
import com.alkaidlab.sunshine.ui.theme.*
import com.alkaidlab.sunshine.ui.viewmodel.SunshineViewModel

@Composable
fun LogsScreen(viewModel: SunshineViewModel, onBack: () -> Unit = {}) {
    val logs by viewModel.logs.collectAsState()
    val list = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var errorsOnly by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var clear by remember { mutableStateOf(false) }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val filtered = remember(logs, query, errorsOnly) {
        logs.filter { line -> (!errorsOnly || line.contains("ERR", true) || line.contains("FAIL", true)) && line.contains(query, true) }
    }
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(filtered, follow) { if (follow && filtered.isNotEmpty()) list.scrollToItem(filtered.lastIndex) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("返回设置")
        }
        PageHeader("日志", "实时记录 · ${logs.size} 条") {
            IconButton(onClick = { clear = true }, enabled = logs.isNotEmpty()) { Icon(Icons.Default.DeleteOutline, "清空日志") }
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("搜索日志") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清除搜索") } },
            shape = MaterialTheme.shapes.small)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = errorsOnly, onClick = { errorsOnly = !errorsOnly }, label = { Text("仅错误") })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { follow = !follow }) {
                Icon(if (follow) Icons.Default.Pause else Icons.Default.VerticalAlignBottom, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(if (follow) "暂停跟随" else "跟随最新")
            }
        }
        Surface(Modifier.fillMaxWidth().weight(1f).padding(bottom = 20.dp),
            color = CardBg, shape = MaterialTheme.shapes.medium) {
            if (filtered.isEmpty()) Box(Modifier.padding(20.dp), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Default.Terminal, if (logs.isEmpty()) "暂无日志" else "没有匹配的日志", "可以更改搜索词或关闭错误筛选。")
            } else LazyColumn(state = list, contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(filtered) { _, line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp,
                        color = if (line.contains("ERR", true) || line.contains("FAIL", true)) AccentRed else TextPrimary)
                }
            }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空当前日志？") },
        text = { Text("清空后无法恢复，新的运行日志仍会继续记录。") },
        confirmButton = { TextButton(onClick = { SunshineLogManager.clear(); clear = false }) { Text("清空", color = AccentRed) } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
}
