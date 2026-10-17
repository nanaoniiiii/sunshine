package com.alkaidlab.sunshine.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alkaidlab.sunshine.BuildConfig
import com.alkaidlab.sunshine.update.AppUpdater
import kotlinx.coroutines.launch

@Composable
fun AppUpdateCard() {
    val state by AppUpdater.state.collectAsState()
    SectionCard("软件更新", Icons.Default.SystemUpdate) {
        Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        Text(state.message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { AppUpdater.open() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (state.ready) "安装已下载的更新" else if (state.release != null) "查看新版本" else "检查更新")
        }
        Text("启动时和后台定期检查官网更新，发现后通知；在应用内下载安装。历史版本保留在 GitHub。",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AppUpdateDialog() {
    val open by AppUpdater.dialogOpen.collectAsState()
    if (!open) return
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsState()
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }
    fun install() {
        scope.launch {
            installing = true
            try {
                val file = AppUpdater.installationFile() ?: return@launch
                context.startActivity(com.alkaidlab.sunshine.update.UpdateInstaller.intent(context, file))
            } catch (e: Exception) { AppUpdater.installationError("无法打开安装界面：${e.message}") }
            finally { installing = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else AppUpdater.installationError("请允许 Sunshine 安装更新后，再点安装更新。")
    }
    LaunchedEffect(Unit) { if (state.release == null) AppUpdater.checkNow() }
    AlertDialog(onDismissRequest = { AppUpdater.dismiss() },
        title = { Text(state.release?.let { "Sunshine ${it.tag}" } ?: "软件更新") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前版本 ${BuildConfig.VERSION_NAME}")
            Text(state.message)
            if (state.downloading) {
                if (state.progress >= 0) {
                    LinearProgressIndicator(progress = state.progress / 100f, modifier = Modifier.fillMaxWidth())
                    Text("${state.progress}%")
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.release?.let { release ->
                val size = if (release.size > 0) " · ${"%.1f".format(release.size / 1048576.0)} MB" else ""
                Text("${release.name}$size${if (release.prerelease) " · 预览版" else ""}", style = MaterialTheme.typography.bodySmall)
                if (release.notes.isNotBlank()) Text(release.notes)
            }
            if (state.ready) Text("安装会重启 Sunshine，当前串流将断开。安装完成后重新打开应用。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = {
            Button(enabled = !state.checking && !state.downloading && !installing, onClick = {
                when {
                    state.ready -> {
                        if (context.packageManager.canRequestPackageInstalls()) install()
                        else try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                        catch (e: Exception) { AppUpdater.installationError("无法打开安装授权：${e.message}") }
                    }
                    state.release != null -> AppUpdater.download()
                    else -> AppUpdater.checkNow()
                }
            }) { Text(when { installing -> "正在校验…"; state.ready -> "安装更新"; state.downloading -> "正在下载…"; state.checking -> "正在检查…"; state.release != null -> "下载更新"; else -> "重新检查" }) }
        },
        dismissButton = { TextButton(onClick = { AppUpdater.dismiss() }) { Text("关闭") } })
}
