package com.alkaidlab.sunshine.service

import com.alkaidlab.sunshine.data.RuntimeSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * FullscreenSessionRunner 的纯 JVM 单测：只覆盖各 early-return 分支与
 * finally 的 busy 释放/空闲恢复语义；成功路径会调用
 * FullscreenDisplay.apply（Android 侧），不在纯 JVM 下测。
 */
class FullscreenSessionRunnerTest {

    private class FakeHost : FullscreenSessionRunner.Host {
        var busy = false
        var nativeThreadId: Long? = 42L
        var active: RuntimeSession? = runtimeSession()
        var shellAvailable = true
        var ready = true
        var sessions: List<RuntimeSession> = emptyList()
        var acquireCalls = 0
        var releaseCalls = 0
        var restoreCalls = 0
        val logs = mutableListOf<Pair<String, String>>()

        override fun tryAcquireFullscreenBusy(): Boolean {
            acquireCalls++
            if (busy) return false
            busy = true
            return true
        }
        override fun releaseFullscreenBusy() { releaseCalls++; busy = false }
        override fun findNativeThreadId(sessionId: Long): Long? = nativeThreadId
        override fun findActiveSession(threadId: Long): RuntimeSession? =
            active?.takeIf { it.videoThreadId == threadId }
        override fun privilegedShellAvailable(): Boolean = shellAvailable
        override fun captureReady(): Boolean = ready
        override fun fullscreenBackend(rememberOriginal: Boolean): FullscreenDisplay.Backend =
            error("unreachable in early-return tests")
        override fun isSessionCurrent(selected: RuntimeSession, sessionId: Long): Boolean = false
        override fun restoreFullscreenIfIdle() { restoreCalls++ }
        override fun hasActiveSessions(): Boolean = sessions.isNotEmpty()
    }

    companion object {
        private fun runtimeSession() = RuntimeSession(
            clientName = "c", clientIp = "1.2.3.4", width = 1920, height = 1080,
            fps = 60, bitrate = 20000, codec = "h264", startTime = 1L, videoThreadId = 42L
        )

        private fun runner(host: FakeHost) =
            FullscreenSessionRunner(host) { tag, msg -> host.logs.add(tag to msg) }
    }

    @Test fun busyContentionFailsFastWithoutReleasing() = runBlocking {
        val host = FakeHost().apply { busy = true }
        val result = runner(host).run(7L)
        assertFalse(result.applied)
        assertEquals("正在切换全屏，请稍候", result.message)
        assertEquals(1, host.acquireCalls)
        assertEquals(0, host.releaseCalls) // 未抢到锁，不释放
        assertEquals(0, host.restoreCalls)
        assertEquals(0, host.logs.size)
    }

    @Test fun missingNativeSessionReleasesBusyAndRestoresWhenIdle() = runBlocking {
        val host = FakeHost().apply { nativeThreadId = null }
        val result = runner(host).run(7L)
        assertFalse(result.applied)
        assertEquals("该连接已结束，请重新选择", result.message)
        assertEquals(1, host.releaseCalls)
        assertEquals(1, host.restoreCalls) // 无活跃会话 → 恢复
    }

    @Test fun missingActiveSessionReleasesBusy() = runBlocking {
        val host = FakeHost().apply { active = null }
        val result = runner(host).run(7L)
        assertFalse(result.applied)
        assertEquals("该连接已结束，请重新选择", result.message)
        assertEquals(1, host.releaseCalls)
    }

    @Test fun shellUnavailableFails() = runBlocking {
        val host = FakeHost().apply { shellAvailable = false }
        val result = runner(host).run(7L)
        assertFalse(result.applied)
        assertEquals("修改本机分辨率需要 Shizuku 远程控制权限", result.message)
        assertEquals(1, host.releaseCalls)
    }

    @Test fun captureNotReadyFails() = runBlocking {
        val host = FakeHost().apply { ready = false }
        val result = runner(host).run(7L)
        assertFalse(result.applied)
        assertEquals("屏幕采集尚未就绪，请稍后重试", result.message)
        assertEquals(1, host.releaseCalls)
    }

    @Test fun restoreSkippedWhenSessionsActive() = runBlocking {
        val host = FakeHost().apply {
            ready = false
            sessions = listOf(runtimeSession())
        }
        val result = runner(host).run(7L)
        assertFalse(result.applied)
        assertEquals("屏幕采集尚未就绪，请稍后重试", result.message)
        assertEquals(1, host.releaseCalls)
        assertEquals(0, host.restoreCalls) // 仍有活跃会话 → 不恢复
    }
}
