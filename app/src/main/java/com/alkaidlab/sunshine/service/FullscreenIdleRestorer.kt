package com.alkaidlab.sunshine.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 全屏闲置恢复触发器（God object 拆分 #7-2）。
 *
 * 承接 SunshineRuntimeManager.restoreFullscreenIfIdle()：在「所有串流会话结束 /
 * 退出虚拟桌面 / Shizuku 输入初始化 / 全屏会话 finally」时，尝试把被改过的物理显示
 * 参数（wm size / rotation）恢复到进入全屏前记住的快照。函数体逐字搬入，语义不变：
 * - prefs 无 "original" 快照直接返回；
 * - 在 fullscreenScope 上异步执行，会话非空 / 特权 shell 不可用 / 抢不到
 *   _fullscreenBusy（compareAndSet）时放弃；
 * - 恢复成功才清除快照并打 FULLSCREEN 日志，异常打 FULLSCREEN_ERR；
 * - finally 必释放 _fullscreenBusy。
 */
internal class FullscreenIdleRestorer(
    private val scope: CoroutineScope,
    private val host: Host,
    private val logger: (tag: String, message: String) -> Unit = SunshineLogManager::log,
) {
    internal interface Host {
        fun hostContext(): Context?
        fun hasActiveSessions(): Boolean
        fun fullscreenBusy(): MutableStateFlow<Boolean>
        fun fullscreenBackend(): FullscreenDisplay.Backend
    }

    fun restoreFullscreenIfIdle() {
        val prefs = host.hostContext()?.getSharedPreferences("fullscreen_display_restore", Context.MODE_PRIVATE) ?: return
        if (!prefs.contains("original")) return
        scope.launch {
            if (host.hasActiveSessions() || !PrivilegedShell.available() || !host.fullscreenBusy().compareAndSet(false, true)) return@launch
            try {
                if (host.hasActiveSessions()) return@launch
                val raw = prefs.getString("original", null) ?: return@launch
                val data = org.json.JSONObject(raw)
                val size = if (data.getInt("width") > 0 && data.getInt("height") > 0)
                    FullscreenDisplay.Size(data.getInt("width"), data.getInt("height")) else null
                val before = FullscreenDisplay.Snapshot(size, 0, autoRotate = data.getBoolean("autoRotate"),
                    userRotation = data.getInt("userRotation"), fixedRotation = data.getBoolean("fixedRotation"))
                if (FullscreenDisplay.restore(before, host.fullscreenBackend())) {
                    prefs.edit().remove("original").commit()
                    logger("FULLSCREEN", "Restored original display after all clients exited")
                }
            } catch (e: Exception) { logger("FULLSCREEN_ERR", "Display restore failed: ${e.message}") }
            finally { host.fullscreenBusy().value = false }
        }
    }
}
