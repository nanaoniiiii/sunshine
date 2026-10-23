package com.alkaidlab.sunshine.service

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Build
import com.alkaidlab.sunshine.data.NativeVideoSession
import com.alkaidlab.sunshine.data.RuntimeSession

/**
 * createVirtualScreen 的前置检查与配置推导（God object 拆分 #6-4，第一步）。
 *
 * 从 SunshineRuntimeManager.createVirtualScreen() 开头逐字搬出：守卫检查、
 * sunshine.conf 解析、width/height/refreshRate/density 等参数推导。
 * DeX 状态机、client 调用等协程编排仍在 manager 内，本轮不动。
 *
 * [resolve] 的执行顺序、异常语义（conf 缺失时 readLines 抛出、与原实现一致在
 * try 之外传播）与原代码逐行等价；公开 API 签名零变化。
 */
internal class VirtualScreenPreflight(private val host: Host) {

    internal interface Host {
        fun virtualScreenBusy(): Boolean
        fun virtualScreenActive(): Boolean
        fun fullscreenBusy(): Boolean
        fun activeSessions(): List<RuntimeSession>
        fun hostContext(): Context?
        fun hasMediaProjection(): Boolean
        fun frameDispatcherRunning(): Boolean
        fun maxFps(): Int
        fun nativeVideoSession(threadId: Long): NativeVideoSession?
    }

    /** 配置推导成功时 createVirtualScreen 后续流程所需的全部参数。 */
    internal data class Config(
        val session: RuntimeSession,
        val mode: String,
        val density: Int,
        val width: Int,
        val height: Int,
        val refreshRate: Int,
        val systemDesktop: Boolean,
        val samsungDex: Boolean,
        val highRefresh: Boolean,
    )

    internal sealed interface Outcome {
        data class Ready(val config: Config) : Outcome
        data class EarlyReturn(val result: FullscreenDisplay.Result) : Outcome
    }

    /** 原 createVirtualScreen 开头的守卫链；失败时返回应直接返回的 Result。 */
    fun resolve(): Outcome {
        if (host.virtualScreenBusy() || host.fullscreenBusy())
            return Outcome.EarlyReturn(FullscreenDisplay.Result(false, "显示正在切换，请稍后重试"))
        if (host.virtualScreenActive())
            return Outcome.EarlyReturn(FullscreenDisplay.Result(true, "虚拟屏已创建"))
        val session = host.activeSessions().firstOrNull()
            ?: return Outcome.EarlyReturn(FullscreenDisplay.Result(false, "先连接 Moonlight，再创建虚拟屏"))
        val ctx = host.hostContext()
            ?: return Outcome.EarlyReturn(FullscreenDisplay.Result(false, "服务未启动"))
        if (!host.hasMediaProjection() || !host.frameDispatcherRunning())
            return Outcome.EarlyReturn(FullscreenDisplay.Result(false, "先允许屏幕录制并建立串流连接"))
        val props = java.io.File(ctx.filesDir, "sunshine.conf").readLines().mapNotNull { line ->
            val parts = line.trim().takeUnless { it.startsWith("#") }?.split('=', limit = 2)
            if (parts?.size == 2) parts[0].trim() to parts[1].trim() else null
        }.toMap()
        val native = host.nativeVideoSession(session.videoThreadId)
        return Outcome.Ready(derive(session, native, props, host.maxFps()))
    }

    /**
     * 纯配置推导（无 Android 运行时依赖，可纯 JVM 单测；[manufacturer] 为测试缝，
     * 默认实参与原代码一致。JVM 下 Build.MANUFACTURER 为 null，此处用可空接收者
     * 的 Any?.equals 调用，不抛 NPE——与设备上行为一致）。
     */
    internal fun derive(
        session: RuntimeSession,
        native: NativeVideoSession?,
        props: Map<String, String>,
        maxFps: Int,
        manufacturer: String? = Build.MANUFACTURER,
    ): Config {
        val mode = if (props["virtual_screen_mode"] == "desktop") "desktop" else "screen_off"
        val density = props["virtual_screen_density"]?.toIntOrNull()?.coerceIn(120, 640) ?: 240
        val width = native?.requestedWidth?.takeIf { it > 0 } ?: session.width
        val height = native?.requestedHeight?.takeIf { it > 0 } ?: session.height
        val refreshRate = minOf(native?.requestedFps?.takeIf { it > 0 } ?: session.fps, maxFps).coerceIn(15, 240)
        val systemDesktop = props["virtual_screen_desktop"] == "system"
        val samsungDex = systemDesktop && manufacturer.equals("samsung", ignoreCase = true)
        val highRefresh = props["experimental_desktop_high_refresh"] != "false"
        return Config(session, mode, density, width, height, refreshRate, systemDesktop, samsungDex, highRefresh)
    }
}
