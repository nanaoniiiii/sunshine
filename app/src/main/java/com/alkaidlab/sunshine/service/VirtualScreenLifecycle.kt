package com.alkaidlab.sunshine.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * 虚拟屏退出/面板控制的协程编排（从 SunshineRuntimeManager 拆出，路线图 #6 第 3 步）。
 *
 * 函数体与原实现逐行等价；外部状态全部经 [Host] 在调用线程回读，
 * 读取点位与原代码一致。公开面（[exitVirtualScreen]/[setVirtualScreenPanelOff] 签名）零变化。
 */
internal class VirtualScreenLifecycle(private val host: Host) {

    internal interface Host {
        fun virtualScreenState(): MutableStateFlow<VirtualScreenState>
        fun hasActiveSessions(): Boolean
        fun restorePhysicalCapture()
        fun displayClient(): VirtualScreenClient
        fun screenClient(): VirtualScreenClient?
    }

    suspend fun exitVirtualScreen(): FullscreenDisplay.Result = withContext(Dispatchers.Main + NonCancellable) {
        val state = host.virtualScreenState()
        if (state.value.busy) return@withContext FullscreenDisplay.Result(false, "显示正在切换，请稍后重试")
        if (!state.value.active) return@withContext FullscreenDisplay.Result(true, "虚拟屏已退出")
        val previous = state.value
        state.value = previous.copy(busy = true)
        try {
            withContext(Dispatchers.IO) { host.screenClient()?.close() }
            state.value = VirtualScreenState(busy = true)
            if (previous.mode == "desktop") host.restorePhysicalCapture()
            SunshineLogManager.log("VIRTUAL_SCREEN", "Released display mode; physical capture restored")
            FullscreenDisplay.Result(true, "已退出虚拟屏并恢复主屏")
        } catch (e: Exception) {
            state.value = if (previous.mode == "screen_off") previous.copy(busy = true, error = e.message)
                else VirtualScreenState(busy = true, error = e.message)
            if (previous.mode == "desktop") try { host.restorePhysicalCapture() } catch (_: Exception) {}
            FullscreenDisplay.Result(false, "虚拟屏退出时出现问题：${e.message}")
        } finally { state.value = state.value.copy(busy = false) }
    }

    suspend fun setVirtualScreenPanelOff(off: Boolean): FullscreenDisplay.Result = withContext(Dispatchers.Main + NonCancellable) {
        val state = host.virtualScreenState()
        val previous = state.value
        if (!previous.active || previous.mode != "desktop") return@withContext FullscreenDisplay.Result(false, "请先创建独立第二屏")
        if (previous.busy) return@withContext FullscreenDisplay.Result(false, "显示正在切换，请稍后重试")
        state.value = previous.copy(busy = true)
        try {
            check(withContext(Dispatchers.IO) { host.displayClient().setMainPanelOff(off) }) { "主屏面板操作未完成" }
            check(host.hasActiveSessions()) { "连接已结束，正在恢复主屏" }
            state.value = previous.copy(busy = true, mainPanelOff = off, error = null)
            SunshineLogManager.log("VIRTUAL_PANEL", "Physical main panel off=$off; virtual display ${previous.displayId} preserved")
            FullscreenDisplay.Result(true, if (off) "主屏面板已关闭，第二屏继续串流" else "主屏面板已恢复")
        } catch (error: Exception) {
            state.value = previous.copy(busy = true, error = (error.cause ?: error).message)
            FullscreenDisplay.Result(false, "主屏面板操作失败：${(error.cause ?: error).message}")
        } finally {
            state.value = state.value.copy(busy = false)
            if (!host.hasActiveSessions() && state.value.active) exitVirtualScreen()
        }
    }
}
