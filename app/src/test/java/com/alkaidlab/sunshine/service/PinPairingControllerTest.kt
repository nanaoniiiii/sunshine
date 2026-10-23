package com.alkaidlab.sunshine.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PinPairingController] 纯 JVM 单测。
 *
 * controller 经 logger 测试缝注入 no-op，避开 android.util.Log；
 * submitToEngine lambda 由各用例按需提供。
 */
class PinPairingControllerTest {

    private fun controller(
        submitToEngine: (String) -> Boolean = { true },
        logs: MutableList<Pair<String, String>> = mutableListOf(),
    ) = PinPairingController(
        submitToEngine = submitToEngine,
        logger = { tag, message -> logs.add(tag to message) },
    )

    @Test
    fun initialState_isNull() {
        assertNull(controller().pinRequested.value)
    }

    @Test
    fun onPinRequested_setsPinRequired() {
        val c = controller()
        c.onPinRequested()
        assertEquals("PIN_REQUIRED", c.pinRequested.value)
    }

    @Test
    fun submitPin_validPin_submitsTrimmedAndClears() {
        var received: String? = null
        val c = controller(submitToEngine = { pin ->
            received = pin
            true
        })
        c.onPinRequested()
        assertTrue(c.submitPin(" 1234\t"))
        assertEquals("1234", received)
        assertNull(c.pinRequested.value)
    }

    @Test
    fun submitPin_tooShort_rejectedWithoutEngineCall() {
        var called = false
        val c = controller(submitToEngine = { called = true; true })
        c.onPinRequested()
        assertFalse(c.submitPin("12"))
        assertFalse(called)
        assertEquals("PIN_REQUIRED", c.pinRequested.value)
    }

    @Test
    fun submitPin_nonDigit_rejectedWithoutEngineCall() {
        var called = false
        val c = controller(submitToEngine = { called = true; true })
        assertFalse(c.submitPin("12a4"))
        assertFalse(called)
        assertNull(c.pinRequested.value)
    }

    @Test
    fun submitPin_engineRejects_keepsRequest() {
        val c = controller(submitToEngine = { false })
        c.onPinRequested()
        assertFalse(c.submitPin("1234"))
        assertEquals("PIN_REQUIRED", c.pinRequested.value)
    }

    @Test
    fun submitPin_engineThrows_returnsFalseAndKeepsRequest() {
        val c = controller(submitToEngine = { throw RuntimeException("native boom") })
        c.onPinRequested()
        assertFalse(c.submitPin("1234"))
        assertEquals("PIN_REQUIRED", c.pinRequested.value)
    }

    @Test
    fun dismissPinRequest_clears() {
        val c = controller()
        c.onPinRequested()
        c.dismissPinRequest()
        assertNull(c.pinRequested.value)
    }

    @Test
    fun clear_clears() {
        val c = controller()
        c.onPinRequested()
        c.clear()
        assertNull(c.pinRequested.value)
    }

    @Test fun logsNeverContainSubmittedValuesIncludingEngineErrors() {
        for (input in listOf("7392", "malformed-secret-value")) {
            val logs = mutableListOf<Pair<String, String>>()
            val c = controller(submitToEngine = { throw IllegalStateException("rejected $input") }, logs = logs)
            c.onPinRequested()
            assertFalse(c.submitPin(input))
            assertTrue(logs.isNotEmpty())
            assertTrue(logs.none { input in it.second })
        }
        val logs = mutableListOf<Pair<String, String>>()
        assertTrue(controller(logs = logs).submitPin("7392"))
        assertTrue(logs.none { "7392" in it.second })
    }
}
