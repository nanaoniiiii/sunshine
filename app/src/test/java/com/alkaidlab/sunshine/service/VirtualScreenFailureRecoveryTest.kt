package com.alkaidlab.sunshine.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutionException

class VirtualScreenFailureRecoveryTest {
    private class FakeHost : VirtualScreenFailureRecovery.Host {
        val calls = mutableListOf<String>()
        var errorStateMessage: String = "<unset>"
        override suspend fun closeVirtualClientBestEffort() { calls += "close" }
        override fun setCreateErrorState(message: String) { calls += "state"; errorStateMessage = message }
        override fun restorePhysicalCapture() { calls += "restore" }
    }

    private val logs = mutableListOf<Pair<String, String>>()
    private val host = FakeHost()
    private val recovery = VirtualScreenFailureRecovery(host) { tag, message -> logs += tag to message }

    @Test fun desktopModeRestoresPhysicalCaptureOnFailure() = runBlocking {
        val error = RuntimeException("boom")
        val result = recovery.handleCreateFailure(error, "desktop")

        assertEquals(listOf("close", "state", "restore"), host.calls)
        assertEquals("boom", host.errorStateMessage)
        assertFalse(result.applied)
        assertEquals("虚拟屏未创建：boom", result.message)
        assertEquals(listOf("VIRTUAL_SCREEN_ERR" to "boom"), logs)
    }

    @Test fun screenOffModeSkipsPhysicalRestore() = runBlocking {
        val result = recovery.handleCreateFailure(RuntimeException("boom"), "screen_off")

        assertEquals(listOf("close", "state"), host.calls)
        assertFalse(result.applied)
        assertTrue(result.message.startsWith("虚拟屏未创建："))
    }

    @Test fun transportEnvelopesAreUnwrappedByDisplayFailureMessage() = runBlocking {
        val error = ExecutionException(RuntimeException("root cause"))
        val result = recovery.handleCreateFailure(error, "desktop")

        assertEquals("root cause", host.errorStateMessage)
        assertEquals("虚拟屏未创建：root cause", result.message)
        assertEquals(listOf("VIRTUAL_SCREEN_ERR" to "root cause"), logs)
    }

    @Test fun nullMessageFallsBackToClassName() = runBlocking {
        val error = RuntimeException(null as String?)
        val result = recovery.handleCreateFailure(error, "desktop")

        assertEquals("RuntimeException", host.errorStateMessage)
        assertEquals("虚拟屏未创建：RuntimeException", result.message)
        assertEquals(listOf("VIRTUAL_SCREEN_ERR" to "RuntimeException"), logs)
    }
}
