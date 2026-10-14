package com.alkaidlab.sunshine.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alkaidlab.sunshine.service.SunshineRuntimeManager
import com.alkaidlab.sunshine.ui.theme.SunshineTheme
import kotlinx.coroutines.launch

/** Independent launcher activity rendered on the shell-owned display. */
class VirtualDesktopActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString() }
        setContent {
            SunshineTheme {
                val runtime = SunshineRuntimeManager.getInstance()
                val state by runtime.virtualScreenState.collectAsState()
                val scope = rememberCoroutineScope()
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Sunshine 虚拟桌面", style = MaterialTheme.typography.titleLarge)
                                Text("${state.width} × ${state.height}", style = MaterialTheme.typography.bodyMedium)
                            }
                            FilledTonalButton(onClick = { scope.launch { runtime.exitVirtualScreen() } }, enabled = !state.busy) {
                                Text("退出虚拟屏")
                            }
                        }
                        Text("在这块屏幕上启动应用", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(apps) { app ->
                                OutlinedButton(onClick = {
                                    val component = android.content.ComponentName(app.activityInfo.packageName, app.activityInfo.name)
                                    scope.launch { runtime.launchVirtualApp(component.flattenToString()) }
                                }, modifier = Modifier.heightIn(min = 72.dp)) { Text(app.loadLabel(packageManager).toString()) }
                            }
                        }
                    }
                }
            }
        }
    }
}
