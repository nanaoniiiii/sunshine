package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class RemoteWakeGateTest {
    @Test fun onlyActiveScreenOffInputRequestsWake() {
        val gate = RemoteWakeGate()
        assertFalse(gate.shouldAttempt(false, false, 0))
        assertFalse(gate.shouldAttempt(true, true, 0))
        assertTrue(gate.shouldAttempt(true, false, 0))
        assertFalse(gate.shouldAttempt(true, false, 999))
        assertTrue(gate.shouldAttempt(true, false, 1000))
    }
    @Test fun reconnectResetAllowsImmediateRetry() {
        val gate = RemoteWakeGate()
        assertTrue(gate.shouldAttempt(true, false, 2000))
        gate.reset()
        assertTrue(gate.shouldAttempt(true, false, 2100))
    }
}
