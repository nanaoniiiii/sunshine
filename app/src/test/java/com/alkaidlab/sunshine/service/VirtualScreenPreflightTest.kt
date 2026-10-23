package com.alkaidlab.sunshine.service

import com.alkaidlab.sunshine.data.NativeVideoSession
import com.alkaidlab.sunshine.data.RuntimeSession
import org.junit.Assert.*
import org.junit.Test

/**
 * [VirtualScreenPreflight] 纯逻辑单测（derive 的配置推导 + resolve 的守卫链）。
 * derive 不触 Android 运行时；resolve 的 Host 用假实现，conf 读取路径不进
 * （守卫提前返回），故可在纯 JVM 下运行。
 */
class VirtualScreenPreflightTest {

    private fun session(width: Int = 1920, height: Int = 1080, fps: Int = 60) = RuntimeSession(
        clientName = "Moonlight", clientIp = "192.168.1.2", width = width, height = height,
        fps = fps, bitrate = 20000, codec = "h264", startTime = 0L, videoThreadId = 7L,
    )

    private fun native(
        requestedWidth: Int = 0,
        requestedHeight: Int = 0,
        requestedFps: Int = 0,
        threadId: Long = 7L,
    ) = NativeVideoSession(
        sessionId = 1L, threadId = threadId, codec = "h264", encoder = "e",
        requestedFps = requestedFps, configuredFps = 60,
        requestedBitrateKbps = 20000, desiredBitrateKbps = 20000, appliedBitrateKbps = 20000,
        bitrateStatus = 0, idrStatus = 0, idrRequests = 0L, encodedFrames = 0L, encodedBytes = 0L,
        keyframes = 0L, invalidBuffers = 0L, encodedFps = 0.0, encodedBitrateKbps = 0.0,
        submitToOutputP95Ms = null, fecPercentage = 0, pendingFecPercentage = 0,
        nominalVideoFecBudgetKbps = 0, controlCeilingKbps = 0, adaptiveEnabled = false,
        adaptiveState = "", controlRttMs = null, fecReportAgeMs = null, fecReports = 0L,
        fecRecoveredBlocks = 0L, fecUnrecoverableBlocks = 0L,
        reportedMissingDataPackets = 0L, fecDuplicateReports = 0L,
        fecRejectedReports = 0L, fecChanges = 0L,
        requestedWidth = requestedWidth, requestedHeight = requestedHeight,
    )

    private class FakeHost(
        var busy: Boolean = false,
        var active: Boolean = false,
        var fullscreenBusy: Boolean = false,
        var sessions: List<RuntimeSession> = emptyList(),
        var hasProjection: Boolean = true,
        var dispatcherRunning: Boolean = true,
    ) : VirtualScreenPreflight.Host {
        override fun virtualScreenBusy() = busy
        override fun virtualScreenActive() = active
        override fun fullscreenBusy() = fullscreenBusy
        override fun activeSessions() = sessions
        override fun hostContext() = null // 守卫测试不走到 conf 读取
        override fun hasMediaProjection() = hasProjection
        override fun frameDispatcherRunning() = dispatcherRunning
        override fun maxFps() = 120
        override fun nativeVideoSession(threadId: Long) = null
    }

    private fun preflight(host: FakeHost) = VirtualScreenPreflight(host)

    private fun early(host: FakeHost): FullscreenDisplay.Result {
        val outcome = preflight(host).resolve()
        assertTrue(outcome is VirtualScreenPreflight.Outcome.EarlyReturn)
        return (outcome as VirtualScreenPreflight.Outcome.EarlyReturn).result
    }

    @Test fun busyOrFullscreenBusyReturnsRetryMessage() {
        assertEquals("显示正在切换，请稍后重试", early(FakeHost(busy = true)).message)
        assertEquals("显示正在切换，请稍后重试", early(FakeHost(fullscreenBusy = true)).message)
        assertFalse(early(FakeHost(busy = true)).applied)
    }

    @Test fun activeScreenReturnsAlreadyCreated() {
        val r = early(FakeHost(active = true))
        assertTrue(r.applied)
        assertEquals("虚拟屏已创建", r.message)
    }

    @Test fun noSessionRequiresMoonlightConnection() {
        val r = early(FakeHost())
        assertFalse(r.applied)
        assertEquals("先连接 Moonlight，再创建虚拟屏", r.message)
    }

    @Test fun missingContextReturnsServiceNotStarted() {
        val r = early(FakeHost(sessions = listOf(session())))
        assertFalse(r.applied)
        assertEquals("服务未启动", r.message)
    }

    @Test fun missingProjectionOrDispatcherReturnsRecordingHint() {
        assertEquals("先允许屏幕录制并建立串流连接", early(FakeHost(sessions = listOf(session()), hasProjection = false)).message)
        assertEquals("先允许屏幕录制并建立串流连接", early(FakeHost(sessions = listOf(session()), dispatcherRunning = false)).message)
    }

    @Test fun deriveDefaultsToScreenOffModeAndSessionGeometry() {
        val cfg = preflight(FakeHost()).derive(session(), null, emptyMap(), 120)
        assertEquals("screen_off", cfg.mode)
        assertEquals(240, cfg.density)
        assertEquals(1920, cfg.width)
        assertEquals(1080, cfg.height)
        assertEquals(60, cfg.refreshRate)
        assertFalse(cfg.systemDesktop)
        assertFalse(cfg.samsungDex) // JVM 下 Build.MANUFACTURER 为 null
        assertTrue(cfg.highRefresh)
    }

    @Test fun deriveClampsDensityAndRefreshRate() {
        val base = preflight(FakeHost())
        assertEquals(120, base.derive(session(), null, mapOf("virtual_screen_density" to "50"), 120).density)
        assertEquals(640, base.derive(session(), null, mapOf("virtual_screen_density" to "900"), 120).density)
        assertEquals(240, base.derive(session(), null, mapOf("virtual_screen_density" to "abc"), 120).density)
        // refreshRate 取 native/session 与 maxFps 的最小值，再 clamp 到 [15, 240]
        assertEquals(120, base.derive(session(fps = 144), null, emptyMap(), 120).refreshRate)
        assertEquals(15, base.derive(session(fps = 5), null, emptyMap(), 120).refreshRate)
        assertEquals(240, base.derive(session(fps = 300), null, emptyMap(), 300).refreshRate)
    }

    @Test fun derivePrefersPositiveNativeDimensionsAndFps() {
        val cfg = preflight(FakeHost()).derive(
            session(), native(requestedWidth = 2560, requestedHeight = 1440, requestedFps = 90),
            mapOf("virtual_screen_mode" to "desktop", "virtual_screen_desktop" to "system"), 120)
        assertEquals("desktop", cfg.mode)
        assertEquals(2560, cfg.width)
        assertEquals(1440, cfg.height)
        assertEquals(90, cfg.refreshRate)
        assertTrue(cfg.systemDesktop)
    }

    @Test fun deriveIgnoresNonPositiveNativeValues() {
        val cfg = preflight(FakeHost()).derive(
            session(), native(requestedWidth = 0, requestedHeight = -1, requestedFps = 0),
            emptyMap(), 120)
        assertEquals(1920, cfg.width)
        assertEquals(1080, cfg.height)
        assertEquals(60, cfg.refreshRate)
    }

    @Test fun deriveReadsHighRefreshFlag() {
        val base = preflight(FakeHost())
        assertTrue(base.derive(session(), null, emptyMap(), 120).highRefresh)
        assertFalse(base.derive(session(), null, mapOf("experimental_desktop_high_refresh" to "false"), 120).highRefresh)
    }

    @Test fun deriveDetectsSamsungDexOnlyOnSamsungSystemDesktop() {
        val base = preflight(FakeHost())
        val sysProps = mapOf("virtual_screen_desktop" to "system")
        assertTrue(base.derive(session(), null, sysProps, 120, manufacturer = "samsung").samsungDex)
        assertTrue(base.derive(session(), null, sysProps, 120, manufacturer = "SAMSUNG").samsungDex)
        assertFalse(base.derive(session(), null, sysProps, 120, manufacturer = "Google").samsungDex)
        assertFalse(base.derive(session(), null, sysProps, 120, manufacturer = null).samsungDex)
        // 非 system 桌面模式下即使是三星也不走 DeX 路径
        assertFalse(base.derive(session(), null, emptyMap(), 120, manufacturer = "samsung").samsungDex)
    }
}
