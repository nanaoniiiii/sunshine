package com.alkaidlab.sunshine.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.alkaidlab.sunshine.service.SunshineRuntimeManager
import com.alkaidlab.sunshine.ui.theme.SunshineTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Session-scoped secondary HOME. Back at the root keeps the desktop in place. */
class VirtualDesktopActivity : ComponentActivity() {
    private var page by mutableStateOf("apps")
    private fun onRequestedDisplay(intent: Intent): Boolean {
        val expected = intent.getIntExtra("desktop_display_id", -1)
        @Suppress("DEPRECATION") val actual = windowManager.defaultDisplay.displayId
        if (actual == 0 || (expected >= 0 && expected != actual)) {
            android.util.Log.e("SunshineDesktop", "Desktop redirected to display $actual; expected $expected")
            finish()
            return false
        }
        return true
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!onRequestedDisplay(intent)) return
        setIntent(intent); page = intent.getStringExtra("desktop_page") ?: "apps"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!onRequestedDisplay(intent)) return
        page = intent.getStringExtra("desktop_page") ?: "apps"
        setContent {
            SunshineTheme {
                val runtime = SunshineRuntimeManager.getInstance()
                val state by runtime.virtualScreenState.collectAsState()
                var joinedSession by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val snacks = remember { SnackbarHostState() }
                var apps by remember { mutableStateOf<List<ResolveInfo>>(emptyList()) }
                var tasks by remember { mutableStateOf<List<Pair<Int, ComponentName>>>(emptyList()) }
                var query by remember { mutableStateOf("") }
                var refresh by remember { mutableIntStateOf(0) }
                var loading by remember { mutableStateOf(true) }
                fun exit() { scope.launch {
                    val result = runtime.leaveSunshineDesktop()
                    if (result.applied) finish() else snacks.showSnackbar(result.message)
                } }
                fun launch(component: ComponentName) { scope.launch {
                    if (!runtime.launchVirtualApp(component.flattenToString())) snacks.showSnackbar("应用未能在此屏幕打开，请重试或从最近任务切换")
                } }
                fun settings(action: String) {
                    val component = Intent(action).resolveActivity(packageManager)
                    if (component != null) launch(component) else scope.launch { snacks.showSnackbar("此设备没有对应的设置入口") }
                }
                LaunchedEffect(state.active, state.busy) {
                    if (state.active) joinedSession = true
                    if (joinedSession && !state.active && !state.busy) finishAndRemoveTask()
                }
                LaunchedEffect(refresh) {
                    loading = true
                    apps = withContext(Dispatchers.IO) {
                        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                            .filter { it.activityInfo.packageName != packageName && it.activityInfo.enabled && it.activityInfo.exported }
                            .distinctBy { it.activityInfo.packageName to it.activityInfo.name }
                            .sortedBy { it.loadLabel(packageManager).toString() }
                    }
                    loading = false
                }
                LaunchedEffect(page, refresh) {
                    if (page == "tasks") {
                        val result = org.json.JSONArray(runtime.virtualDesktopTasks())
                        tasks = (0 until result.length()).mapNotNull { index ->
                            val item = result.getJSONObject(index)
                            ComponentName.unflattenFromString(item.optString("component"))?.let { item.getInt("id") to it }
                        }
                    }
                }
                BackHandler { page = "apps"; query = "" }
                Scaffold(snackbarHost = { SnackbarHost(snacks) }, bottomBar = {
                    Surface(tonalElevation = 4.dp) {
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { page = "apps"; query = "" }) { Text("返回") }
                            OutlinedButton(onClick = { page = "apps"; query = "" }) { Text("全部应用") }
                            OutlinedButton(onClick = { page = "tasks"; refresh++ }) { Text("最近任务") }
                            OutlinedButton(onClick = { page = "quick" }) { Text("快捷控制") }
                            FilledTonalButton(onClick = { exit() }, enabled = !state.busy) { Text("退出桌面") }
                        }
                    }
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Sunshine 桌面", style = MaterialTheme.typography.titleLarge)
                                Text("${state.width} × ${state.height} · ${if (page == "tasks") "此屏幕的最近任务" else if (page == "quick") "快捷控制" else "全部应用"}", style = MaterialTheme.typography.bodyMedium)
                            }
                            FilledTonalButton(onClick = { exit() }, enabled = !state.busy) { Text("退出桌面") }
                        }
                        if (state.detail.isNotBlank()) Text(state.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text("返回和主页键停留在此桌面。“退出桌面”返回手机主屏，保留虚拟屏与全屏息屏串流；主机端或 Ctrl + Alt + Esc 可结束虚拟屏。", style = MaterialTheme.typography.bodySmall)
                        if (page == "apps") {
                            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索应用") })
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { refresh++ }) { Text("刷新应用") }
                                TextButton(onClick = { settings(android.provider.Settings.ACTION_SETTINGS) }) { Text("系统设置") }
                            }
                            val filtered = apps.filter { it.loadLabel(packageManager).toString().contains(query, true) || it.activityInfo.packageName.contains(query, true) }
                            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (!loading && filtered.isEmpty()) Text(if (apps.isEmpty()) "未读取到应用。可以刷新，或先打开系统设置；退出桌面始终可用。" else "没有匹配的应用")
                            LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(filtered, key = { it.activityInfo.packageName + "/" + it.activityInfo.name }) { app ->
                                    OutlinedCard(onClick = { launch(ComponentName(app.activityInfo.packageName, app.activityInfo.name)) }) {
                                        Row(Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            val icon = remember(app.activityInfo.packageName) { runCatching { app.loadIcon(packageManager).toBitmap(64, 64).asImageBitmap() }.getOrNull() }
                                            if (icon != null) Image(icon, null, Modifier.size(36.dp))
                                            Text(app.loadLabel(packageManager).toString(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        } else if (page == "tasks") {
                            TextButton(onClick = { refresh++ }) { Text("刷新任务") }
                            if (tasks.isEmpty()) Text("此屏幕没有其他应用任务。可从全部应用打开应用。")
                            LazyVerticalGrid(GridCells.Adaptive(220.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(tasks, key = { it.first }) { (id, component) ->
                                    val label = remember(component) { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(component.packageName, 0)).toString() }.getOrDefault(component.packageName) }
                                    OutlinedButton(onClick = { scope.launch { if (!runtime.virtualDesktopAction("focus", id)) snacks.showSnackbar("任务已结束或无法切换，请刷新") } }, Modifier.heightIn(min = 72.dp)) { Text(label) }
                                }
                            }
                        } else {
                            Text("三星原生 DeX 使用系统任务栏和通知栏。普通第二屏是否有系统通知栏由固件决定；这里提供应用、设置和退出入口。")
                            for ((label, action) in listOf("网络设置" to android.provider.Settings.ACTION_WIFI_SETTINGS, "显示设置" to android.provider.Settings.ACTION_DISPLAY_SETTINGS, "声音设置" to android.provider.Settings.ACTION_SOUND_SETTINGS, "系统设置" to android.provider.Settings.ACTION_SETTINGS))
                                OutlinedButton(onClick = { settings(action) }, Modifier.fillMaxWidth()) { Text(label) }
                            FilledTonalButton(onClick = { exit() }, Modifier.fillMaxWidth()) { Text("退出 Sunshine 桌面，保留全屏") }
                        }
                    }
                }
            }
        }
    }
}
