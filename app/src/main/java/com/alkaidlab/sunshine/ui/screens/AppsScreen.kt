package com.alkaidlab.sunshine.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import com.alkaidlab.sunshine.data.AppItem
import com.alkaidlab.sunshine.ui.components.*
import com.alkaidlab.sunshine.ui.theme.*
import com.alkaidlab.sunshine.ui.viewmodel.SunshineViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsScreen(viewModel: SunshineViewModel) {
    val apps by viewModel.apps.collectAsState()
    val installed by viewModel.installedApps.collectAsState()
    val loading by viewModel.appsLoading.collectAsState()
    val loadError by viewModel.installedAppsError.collectAsState()
    val saving by viewModel.appSaving.collectAsState()
    var picker by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var delete by remember { mutableStateOf<AppItem?>(null) }
    var modeTarget by remember { mutableStateOf<AppItem?>(null) }
    var singleApp by remember { mutableStateOf(false) }
    val choices = remember(installed, search) { installed.filter { it.label.contains(search, true) || it.packageName.contains(search, true) } }
    LaunchedEffect(Unit) { viewModel.fetchApps() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding,
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageHeader("应用", "选择入口，在 Moonlight 中直接打开") }
        item { PrimaryButton(onClick = { search = ""; picker = true; viewModel.loadInstalledApps() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("添加应用")
        } }
        if (apps.isEmpty()) item { SectionCard { EmptyState(Icons.Default.Apps, "暂无应用入口", "点击添加应用，从本机可启动的应用中选择。") } }
        items(apps, key = { it.id ?: it.name }) { app ->
            val desktop = app.cmd.isNullOrBlank()
            SectionCard(modifier = if (desktop) Modifier else Modifier.combinedClickable(
                onClick = { viewModel.launchApp(app) }, onLongClick = { modeTarget = app; singleApp = app.singleApp },
                onLongClickLabel = "设置串流模式")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                        Icon(if (desktop) Icons.Default.DesktopWindows else Icons.Default.Apps, null,
                            tint = PrimaryCyan, modifier = Modifier.padding(12.dp).size(24.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(app.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (desktop) "镜像完整设备屏幕" else app.cmd.orEmpty(), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!desktop) IconButton(onClick = { modeTarget = app; singleApp = app.singleApp }) { Icon(Icons.Default.MoreVert, "${app.name} 的串流选项") }
                }
                if (desktop) Text("默认桌面入口", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                else {
                    StatusTag(if (app.singleApp) "仅此应用 · 离开后结束此连接" else "默认 · 可自由切换应用")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { viewModel.launchApp(app) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("在设备上打开")
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { delete = app }) { Icon(Icons.Default.DeleteOutline, "删除 ${app.name} 入口", tint = AccentRed) }
                    }
                }
            }
        }
    }
    if (picker) Dialog(onDismissRequest = { if (!saving) picker = false }) {
        Surface(shape = MaterialTheme.shapes.large, color = CardBg) {
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择本机应用", style = MaterialTheme.typography.titleLarge)
                Text("仅读取可启动应用的名称和包名。选择后会显示在 Moonlight 中。", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(search, { search = it }, label = { Text("搜索名称或包名") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) })
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (loadError != null) {
                    Text(loadError.orEmpty(), color = AccentRed)
                    TextButton(onClick = { viewModel.loadInstalledApps() }) { Text("重新读取") }
                }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!loading && choices.isEmpty()) item { Text("没有找到匹配的应用", color = TextSecondary, modifier = Modifier.padding(vertical = 20.dp)) }
                    items(choices, key = { it.packageName }) { candidate ->
                        val already = apps.any { it.cmd == candidate.packageName }
                        TextButton(onClick = {
                            var label = candidate.label
                            if (apps.any { it.name == label }) label += " (${candidate.packageName})"
                            viewModel.addApp(AppItem(name = label, cmd = candidate.packageName)) { if (it) picker = false }
                        }, enabled = !already && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentPadding = PaddingValues(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(candidate.label, style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Start, modifier = Modifier.fillMaxWidth())
                                Text(candidate.packageName, color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Start, modifier = Modifier.fillMaxWidth())
                            }
                            if (already) Text("已添加", style = MaterialTheme.typography.labelMedium)
                            else Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                if (saving) Text("正在保存入口…", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { picker = false }, enabled = !saving, modifier = Modifier.align(Alignment.End)) { Text("取消") }
            }
        }
    }
    modeTarget?.let { app ->
        AlertDialog(onDismissRequest = { if (!saving) modeTarget = null }, title = { Text("${app.name} 的串流模式") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeChoice("默认", "打开应用后可回到桌面或切换到其他应用。", !singleApp, !saving) { singleApp = false }
                ModeChoice("仅允许此应用", "离开后仅结束从此入口发起的连接。通知和悬浮窗口不会触发退出。", singleApp, !saving) { singleApp = true }
                Text("下次从 Moonlight 打开此入口时生效。受限模式需要已连接并授权的 Shizuku。", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            } }, confirmButton = { TextButton(enabled = !saving, onClick = { viewModel.addApp(app.copy(singleApp = singleApp)) { if (it) modeTarget = null } }) { Text(if (saving) "保存中…" else "保存") } },
            dismissButton = { TextButton(onClick = { modeTarget = null }, enabled = !saving) { Text("取消") } })
    }
    delete?.let { app -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除 ${app.name} 入口？") },
        text = { Text("仅删除 Moonlight 中的入口，设备上的应用仍保留。") },
        confirmButton = { TextButton(onClick = { viewModel.deleteApp(app.name); delete = null }) { Text("删除", color = AccentRed) } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } }) }
}

@Composable
private fun ModeChoice(title: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
    }
}
