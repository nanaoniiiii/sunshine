package com.alkaidlab.sunshine.service

import android.content.Context
import java.util.concurrent.TimeUnit

/** Tablet DeX uses the physical display. Only undo a transition initiated by this instance. */
@android.annotation.SuppressLint("WrongConstant") // Samsung's service is not in the AOSP string enum.
internal class SamsungStandaloneDex(private val context: Context) : AutoCloseable {
    private var owned = false
    fun start() {
        check(supported()) { "此系统不支持平板主屏 DeX" }
        if (NativeDexMirror.activeDisplay(context) != null) return
        check(!NativeDexMirror.hasExternalConnection(context)) { "请保留现有外屏 DeX 会话" }
        val manager = context.getSystemService("desktopmode") ?: error("缺少三星桌面服务")
        val state = manager.javaClass.getMethod("getDesktopModeState").invoke(manager)
        val enabled = state.javaClass.getMethod("getEnabled").invoke(state) as Int
        check(enabled == 2) { "三星 DeX 正在切换，请稍后重试" }
        owned = true // A timeout still has to restore a transition we requested.
        request(true)
    }
    override fun close() {
        if (!owned) return
        val manager = context.getSystemService("desktopmode")
        fun state(): Any? = manager?.javaClass?.getMethod("getDesktopModeState")?.invoke(manager)
        val current = state()
        if (current != null && current.javaClass.getMethod("getDisplayType").invoke(current) == 102) {
            owned = false // The user has since switched to an independent external DeX session.
            return
        }
        request(false)
        owned = false
        val deadline = android.os.SystemClock.uptimeMillis() + 8000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val value = state() ?: return
            if (value.javaClass.getMethod("getEnabled").invoke(value) == 2) return
            Thread.sleep(100)
        }
        error("平板 DeX 退出尚未完成，请在主机关闭 DeX")
    }
    private fun request(enable: Boolean) {
        val process = ProcessBuilder("am", "broadcast", "--user", "0", "-a",
            "com.samsung.android.desktopmode.action.DESKTOP_MODE_UPDATE_REQUEST", "--ei",
            "com.samsung.android.desktopmode.extra.DESKTOP_MODE_STATE", if (enable) "1" else "2", "--ei",
            "com.samsung.android.desktopmode.extra.DESKTOP_MODE_SOURCE", "3").redirectErrorStream(true).start()
        try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "三星 DeX 切换请求超时" }
            val result = process.inputStream.bufferedReader().readText()
            check(process.exitValue() == 0 && !result.contains("SecurityException")) { result.take(512) }
        } finally { process.destroy() }
    }
    companion object {
        fun supported(): Boolean = try {
            Class.forName("com.samsung.android.desktopmode.DesktopModeFeature").getField("SUPPORT_STANDALONE").getBoolean(null)
        } catch (_: Exception) { false }
    }
}
