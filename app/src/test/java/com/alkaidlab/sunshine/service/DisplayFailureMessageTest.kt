package com.alkaidlab.sunshine.service

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

class DisplayFailureMessageTest {
    @Test fun shizukuStartupInstructionsSurviveTimeoutCause() {
        val hint = "请打开授权管理器，取消休眠或后台限制，再返回重试"
        val timeout = runCatching { runBlocking { withTimeout(1) { awaitCancellation() } } }.exceptionOrNull()!!
        assertEquals(TimeoutCancellationException::class.java, timeout.javaClass)
        assertEquals(hint, DisplayFailureMessage.from(IllegalStateException(hint,
            timeout)))
    }

    @Test fun reflectionEnvelopesDoNotHideNativeFailure() {
        assertEquals("display permission denied", DisplayFailureMessage.from(
            InvocationTargetException(SecurityException("display permission denied"), "reflection failed")))
    }

    @Test fun nestedAsyncEnvelopesKeepDisplayContext() {
        val context = "三星 DeX 采集未连接：系统未创建显示"
        assertEquals(context, DisplayFailureMessage.from(CompletionException(ExecutionException(
            IllegalStateException(context, IllegalArgumentException("bad surface"))))))
    }

    @Test fun blankMessageFallsBackToCause() {
        assertEquals("native failure", DisplayFailureMessage.from(IllegalStateException(" ", Exception("native failure"))))
    }

    @Test fun missingMessagesHaveReadableFallback() {
        assertEquals("IllegalStateException", DisplayFailureMessage.from(IllegalStateException()))
    }
}
