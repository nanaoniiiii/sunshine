package com.alkaidlab.sunshine.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alkaidlab.sunshine.ui.components.*
import com.alkaidlab.sunshine.ui.theme.*
import com.alkaidlab.sunshine.ui.viewmodel.SunshineViewModel

@Composable
fun ExperimentalScreen(viewModel: SunshineViewModel, onBack: () -> Unit = {}) {
    var testingController by remember { mutableStateOf(false) }
    if (testingController) { ControllerTestScreen { testingController = false }; return }
    val draft by viewModel.settingsDraft.collectAsState()
    val state by viewModel.virtualScreenState.collectAsState()
    val saveError by viewModel.settingsSaveError.collectAsState()
    val startup by viewModel.serviceStartup.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("返回设置")
            }
            PageHeader("实验性功能", "全局手柄 / 遥控适配器与第二屏")
        }
        if (saveError != null) item { SectionCard("设置未保存", Icons.Default.ErrorOutline) {
            Text(saveError.orEmpty(), color = AccentRed)
            TextButton(onClick = viewModel::retrySettingsSave) { Text("重试") }
        } }
        item { SectionCard("启动与保活", Icons.Default.PowerSettingsNew) {
            SettingSwitch("开机自动启动服务", "设备开机解锁后自动启动 Sunshine；应用更新后也恢复服务", startup.startOnBoot) {
                viewModel.setServiceStartup(startOnBoot = it)
            }
            SettingSwitch("服务常驻与异常恢复", "保留前台通知，服务被系统回收后请求自动重建；主动停止服务时不会自行重开", startup.keepAlive) {
                viewModel.setServiceStartup(keepAlive = it)
            }
            Text("这些设置会保留到下次开机。普通应用无法成为系统服务；强行停止、撤销权限或厂商后台限制仍可能阻止启动。",
                style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text("服务启动后，Shizuku 可用时自动恢复控制；设备重启后可能需要重新启动 Shizuku，并在 Sunshine 中确认屏幕录制。",
                style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        } }
        item { ControllerSettings { testingController = true } }
        item { SectionCard("三星 DeX", Icons.Default.DesktopWindows) {
            Text("在设置 → 虚拟屏打开“第二屏桌面模式”，再选择设备自带桌面（DeX 等）。默认开启虚拟屏只进行主屏息屏串流。",
                color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Text("支持 DeX for PC 的手机使用 Sunshine Local DeX，请确认连接。独立 DeX 第二屏按创建时 Moonlight 请求的分辨率运行，保留三星原生主页和任务栏。平板可直接打开系统 DeX 后串流主屏，无需创建第二屏。普通第二屏使用 Sunshine 主页。",
                color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        } }
        item { SectionCard("第二屏刷新率", Icons.Default.Speed) {
            SettingSwitch("请求高刷新率", "按 Moonlight 请求帧率配置第二屏，无需更改接收端程序", draft?.get("experimental_desktop_high_refresh") != "false", draft != null) {
                viewModel.editSetting("experimental_desktop_high_refresh", it.toString())
            }
            Text("例如接收端请求 120 FPS 时，尝试创建 120 Hz 第二屏。显示刷新率与串流帧率分别记录，便于确认实际效果。",
                color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            Text("部分较旧系统固定虚拟屏为 60 Hz。此开关请求系统支持的刷新率；串流补帧不会计为桌面刷新率提升。",
                color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        } }
        item { SectionCard("当前第二屏", Icons.Default.Info) {
            if (state.active && state.mode == "desktop") {
                Text(state.launcher, style = MaterialTheme.typography.titleMedium)
                Text("${state.width} × ${state.height} · ${state.actualRefreshRate} Hz", color = PrimaryCyan)
                Text("请求刷新率：${state.requestedRefreshRate} Hz", color = TextSecondary)
                SettingSwitch("关闭主屏面板", "保留第二屏渲染，退出第二屏时恢复主屏", state.mainPanelOff, !state.busy) {
                    viewModel.setVirtualScreenPanelOff(it)
                }
                if (state.systemDexMirror && state.displayId == 0)
                    Text("当前串流的是平板主屏 DeX，不是独立第二屏。", color = TextSecondary)
                if (state.detail.isNotBlank()) Text(state.detail, color = TextSecondary)
            } else Text("先在设置中打开第二屏桌面模式并连接 Moonlight，再点击首页“开启虚拟屏”。配置在下次开启时生效。", color = TextSecondary)
        } }
    }
}
