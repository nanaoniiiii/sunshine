package com.alkaidlab.sunshine.service

import com.alkaidlab.sunshine.data.RuntimeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 全屏会话切换编排：把原来内联在 SunshineRuntimeManager 里的
 * `fullscreenSession()`（busy 互斥 + 会话查找 + 特权/采集就绪检查 +
 * FullscreenDisplay.apply + 空闲恢复）抽出。
 *
 * God object 拆分路线图 #7（全屏生命周期）第 3 步。
 *
 * 行为等价：`withContext(Dispatchers.IO + NonCancellable)` 调度不变；
 * `_fullscreenBusy` 仍是 manager 的同一个 MutableStateFlow 原子操作
 * （tryAcquire/release 经 Host 委托到同一实例）；
 * isCurrent 回调仍在 apply 调用时读取同一状态；
 * finally 的 busy 释放 + 空闲恢复顺序不变；公开 API 签名零变化。
 */
internal class FullscreenSessionRunner(
    private val host: Host,
    private val logger: (tag: String, message: String) -> Unit = SunshineLogManager::log
) {

    /** manager 侧提供的状态/操作；所有方法都在原 withContext 上下文的调用线程上执行。 */
    internal interface Host {
        /** 原 `_fullscreenBusy.compareAndSet(false, true)` */
        fun tryAcquireFullscreenBusy(): Boolean
        /** 原 `_fullscreenBusy.value = false` */
        fun releaseFullscreenBusy()
        /** 原 `engineClient?.videoSessions()?.firstOrNull { it.sessionId == sessionId }?.threadId` */
        fun findNativeThreadId(sessionId: Long): Long?
        /** 原 `activeSessions.firstOrNull { it.videoThreadId == threadId }` */
        fun findActiveSession(threadId: Long): RuntimeSession?
        /** 原 `PrivilegedShell.available()` */
        fun privilegedShellAvailable(): Boolean
        /** 原 `frameDispatcher.isRunning && virtualDisplay != null`（失败条件取反） */
        fun captureReady(): Boolean
        /** 原 `fullscreenBackend(rememberOriginal)` */
        fun fullscreenBackend(rememberOriginal: Boolean): FullscreenDisplay.Backend
        /** 原局部函数 isCurrent()：调用时读取同一状态 */
        fun isSessionCurrent(selected: RuntimeSession, sessionId: Long): Boolean
        /** 原 `restoreFullscreenIfIdle()` */
        fun restoreFullscreenIfIdle()
        /** 原 `activeSessions.isNotEmpty()` */
        fun hasActiveSessions(): Boolean
    }

    suspend fun run(sessionId: Long): FullscreenDisplay.Result = withContext(Dispatchers.IO + NonCancellable) {
        if (!host.tryAcquireFullscreenBusy())
            return@withContext FullscreenDisplay.Result(false, "正在切换全屏，请稍候")
        try {
            val threadId = host.findNativeThreadId(sessionId)
                ?: return@withContext FullscreenDisplay.Result(false, "该连接已结束，请重新选择")
            val selected = host.findActiveSession(threadId)
                ?: return@withContext FullscreenDisplay.Result(false, "该连接已结束，请重新选择")
            if (!host.privilegedShellAvailable())
                return@withContext FullscreenDisplay.Result(false, "修改本机分辨率需要 Shizuku 远程控制权限")
            if (!host.captureReady())
                return@withContext FullscreenDisplay.Result(false, "屏幕采集尚未就绪，请稍后重试")
            val backend = host.fullscreenBackend(rememberOriginal = true)
            val result = FullscreenDisplay.apply(
                FullscreenDisplay.Size(selected.width, selected.height),
                backend,
                { host.isSessionCurrent(selected, sessionId) }
            )
            logger("FULLSCREEN", "Session $sessionId: ${result.message}")
            result
        } finally {
            host.releaseFullscreenBusy()
            if (!host.hasActiveSessions()) host.restoreFullscreenIfIdle()
        }
    }
}
