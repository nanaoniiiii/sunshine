package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class FrameSubmissionBudgetTest {
    @Test fun codecSkippedInputDoesNotWaitForTimeoutOrFreeEncodedPackets() {
        val budget = FrameSubmissionBudget()
        for (n in 1..4) budget.submitted(n * 1_000_000L)
        assertFalse(budget.encoded(1000))
        assertTrue(budget.encoded(3000)) // Codec omitted input 2.
        assertEquals(1, budget.encoderSkipped)
        assertEquals(3, budget.pendingCount)
        assertTrue(budget.complete(3000)) // Newer packet sent before older packet.
        assertFalse(budget.encoded(4000))
        assertEquals(2, budget.pendingCount) // Packet 1 is still on the wire.
        assertTrue(budget.complete(1000))
        assertTrue(budget.complete(4000))
        assertEquals(0, budget.pendingCount)
        assertEquals(0, budget.expired)
    }
    @Test fun earlySendAckAndOldCodecProgressDoNotCorruptNewInputBudget() {
        val budget = FrameSubmissionBudget()
        budget.submitted(10_000_000);budget.submitted(11_000_000);budget.submitted(12_000_000)
        assertFalse(budget.encoded(1000)) // Previous surface.
        assertFalse(budget.encoded(10000))
        assertTrue(budget.complete(12000)) // Cross-thread send ACK wins the race.
        assertTrue(budget.encoded(12000)) // Input 11 was skipped; encoded 10 stays held.
        assertEquals(1, budget.pendingCount)
        assertTrue(budget.complete(10000))
        assertEquals(1, budget.encoderSkipped)
    }
    @Test fun boundsEntirePipelineAndMatchesCodecMicrosecondPrecision() {
        val budget = FrameSubmissionBudget()
        for (n in 1..4) budget.submitted(n * 1_000_000L + 123)
        assertEquals(4, budget.pendingCount)
        assertTrue(budget.nextAvailableNs(10_000_000) > 10_000_000)
        assertTrue(budget.complete(2000))
        assertEquals(0, budget.nextAvailableNs(10_000_000))
        assertEquals(3, budget.pendingCount)
    }
    @Test fun queueDiscardAcknowledgesOnlyItsOwnFrameAndClientsStayIndependent() {
        val first = FrameSubmissionBudget(2)
        val second = FrameSubmissionBudget(2)
        first.submitted(1_000_000);first.submitted(2_000_000)
        second.submitted(1_000_000);second.submitted(2_000_000)
        assertTrue(first.complete(2000))
        assertFalse(first.complete(9999))
        assertEquals(1, first.pendingCount)
        assertEquals(2, second.pendingCount)
        assertTrue(second.nextAvailableNs(3_000_000) > 3_000_000)
    }
    @Test fun lostCallbackHasBoundedRecoveryAndOldSurfaceCannotAcknowledgeNewFrames() {
        val budget = FrameSubmissionBudget(1, 10_000_000)
        budget.submitted(1_000_123)
        assertEquals(11_000_000, budget.nextAvailableNs(2_000_000))
        assertEquals(0, budget.nextAvailableNs(11_000_000))
        assertEquals(1, budget.expired)
        budget.submitted(12_000_000)
        assertFalse(budget.complete(1000))
        assertEquals(1, budget.pendingCount)
    }
}
