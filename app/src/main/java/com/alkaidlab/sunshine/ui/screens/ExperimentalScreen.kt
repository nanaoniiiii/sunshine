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
    val draft by viewModel.settingsDraft.collectAsState()
    val state by viewModel.virtualScreenState.collectAsState()
    val saveError by viewModel.settingsSaveError.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("返回设置")
            }
            PageHeader("实验性功能", "第二屏桌面与高刷新率")
        }
        if (saveError != null) item { SectionCard("设置未保存", Icons.Default.ErrorOutline) {
            Text(saveError.orEmpty(), color = AccentRed)
            TextButton(onClick = viewModel::retrySettingsSave) { Text("重试") }
        } }
        item { SectionCard("三星 DeX", Icons.Default.DesktopWindows) {
            SettingSwitch("启用三星 DeX", "按本机系统能力选择原生桌面路径", draft?.get("experimental_samsung_dex") == "true", draft != null) {
                viewModel.editSetting("experimental_samsung_dex", it.toString())
            }
            Text("已有原生 DeX 时直接串流。支持 DeX for PC 的手机使用 Sunshine Local DeX，请确认连接；支持 Standalone 的平板使用主屏原生 DeX（不独立于平板主屏），退出恢复本次切换。固件没有可用入口时提供带应用、任务和退出控制的兼容桌面，并显示原因。已有 DeX 会话保留。",
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
            } else Text("先选择第二屏模式并连接 Moonlight，再在主页创建虚拟屏。配置在下次创建时生效。", color = TextSecondary)
        } }
    }
}
