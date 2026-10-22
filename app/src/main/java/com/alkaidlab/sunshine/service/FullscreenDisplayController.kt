package com.alkaidlab.sunshine.service

import android.content.ContentResolver
import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 全屏显示后端工厂：把原来内联在 SunshineRuntimeManager 里的
 * `fullscreenBackend()`（特权 wm/dumpsys shell 构建 FullscreenDisplay.Backend 匿名对象）
 * 与 `rememberFullscreenDisplay()`（原始显示设置的 SharedPreferences 持久化）抽出。
 *
 * God object 拆分路线图 #7（全屏生命周期）第 1 步：只搬 backend()/rememberFullscreenDisplay()，
 * restoreFullscreenIfIdle()/fullscreenSession() 的协程编排与 _fullscreenBusy 仍留在 manager，
 * 后续轮次再拆。
 *
 * 行为等价：函数体逐字搬运；manager 侧状态全部经 Host 接口在调用线程上读取，
 * 读取点位/线程与原内联代码一致（Backend 匿名对象的方法本就运行在调用线程；
 * syncCapture 内部的 latch 逻辑仍 post 到 captureHandler 执行）。
 *
 * 合并顺序注意：Host.captureDisplaySize/syncCaptureSize 目前委托 manager 的同名私有函数；
 * 若 PR #12（采集几何 → CaptureCanvasSync）先合，rebase 时把这两个委托改接到
 * captureCanvasSync 即可，Backend 匿名对象本体不受影响。
 */
internal class FullscreenDisplayController(private val host: Host) {

    /** manager 侧提供的状态读取；所有方法都在 Backend 方法的调用线程上执行。 */
    internal interface Host {
        /** 原 `captureDisplayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0` */
        fun captureDisplayRotation(): Int
        /** 原 `context?.contentResolver` */
        fun contentResolver(): ContentResolver?
        /** 原 `displayCaptureSize(width, height)` */
        fun captureDisplaySize(width: Int, height: Int): IntArray
        /** 原 `frameDispatcher.isRunning` */
        fun isFrameDispatcherRunning(): Boolean
        /** 原 `virtualDisplay != null` */
        fun hasVirtualDisplay(): Boolean
        /** 原 `syncCaptureSize()`（@Synchronized 语义由被委托函数保持） */
        fun syncCaptureSize(): Boolean
        /** 原 `captureHandler.post { ... }` */
        fun postToCaptureHandler(action: () -> Unit)
        /** 原 `context?.getSharedPreferences("fullscreen_display_restore", Context.MODE_PRIVATE)` */
        fun fullscreenRestorePrefs(): SharedPreferences?
    }

    fun backend(rememberOriginal: Boolean = false): FullscreenDisplay.Backend {
        // Some Android versions print valid wm help with exit code -1 (255); mutations still require 0.
        val help = PrivilegedShell.run(arrayOf("wm", "help"), acceptedExitCodes = setOf(0, 1, 255)).orEmpty()
        val fixedCommand = FullscreenDisplay.rotationCommand(help, fixed = true)
        val rotationCommand = FullscreenDisplay.rotationCommand(help)
        return object : FullscreenDisplay.Backend {
            override fun rotation() = host.captureDisplayRotation()
            override fun snapshot(): FullscreenDisplay.Snapshot? {
                val output = PrivilegedShell.run(arrayOf("wm", "size")) ?: return null
                val windows = PrivilegedShell.run(arrayOf("dumpsys", "window", "displays")) ?: return null
                val fixed = FullscreenDisplay.parseFixedRotation(windows) ?: return null
                val resolver = host.contentResolver() ?: return null
                return FullscreenDisplay.parseOverride(output)?.copy(rotation = rotation(), fixedRotation = fixed,
                    autoRotate = android.provider.Settings.System.getInt(resolver, "accelerometer_rotation", 0) != 0,
                    userRotation = android.provider.Settings.System.getInt(resolver, "user_rotation", rotation()))
                    ?.also { if (rememberOriginal) rememberFullscreenDisplay(it) }
            }
            override fun setFixedRotation(enabled: Boolean): Boolean = fixedCommand != null &&
                PrivilegedShell.run(arrayOf("wm", fixedCommand, if (enabled) "enabled" else "disabled"), 3000) != null
            override fun setRotation(rotation: Int, autoRotate: Boolean): Boolean = rotationCommand != null &&
                PrivilegedShell.run(if (autoRotate) arrayOf("wm", rotationCommand, "free")
                    else arrayOf("wm", rotationCommand, "lock", rotation.toString()), 3000) != null
            override fun setOverride(size: FullscreenDisplay.Size?) =
                PrivilegedShell.run(arrayOf("wm", "size", size?.toString() ?: "reset"), 3000) != null
            override fun awaitDisplay(size: FullscreenDisplay.Size): Boolean {
                repeat(30) {
                    val current = host.captureDisplaySize(0, 0)
                    if (current[0] == size.width && current[1] == size.height) return true
                    Thread.sleep(50)
                }
                return false
            }
            override fun syncCapture(): Boolean {
                val latch = CountDownLatch(1)
                var synced = false
                host.postToCaptureHandler {
                    try { synced = !host.isFrameDispatcherRunning() || !host.hasVirtualDisplay() || host.syncCaptureSize() }
                    finally { latch.countDown() }
                }
                return latch.await(6, TimeUnit.SECONDS) && synced
            }
        }
    }

    private fun rememberFullscreenDisplay(before: FullscreenDisplay.Snapshot) {
        val prefs = host.fullscreenRestorePrefs() ?: error("No host context")
        if (prefs.contains("original")) return
        val data = org.json.JSONObject().put("width", before.override?.width ?: 0)
            .put("height", before.override?.height ?: 0).put("userRotation", before.userRotation)
            .put("autoRotate", before.autoRotate).put("fixedRotation", before.fixedRotation)
        check(prefs.edit().putString("original", data.toString()).commit())
    }
}
