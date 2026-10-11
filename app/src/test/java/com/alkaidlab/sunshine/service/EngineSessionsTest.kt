package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class EngineSessionsTest {
    @Test fun oneClientLeavingCannotRestartOtherClients() {
        val sessions = EngineSessions()
        sessions.started(101)
        sessions.started(102)
        val revision = sessions.stopped(101)!!
        assertFalse(sessions.canRestart(revision))
        assertFalse(sessions.canRestart(EngineSessions.MANUAL_RESTART))
        assertTrue(sessions.canRestart(sessions.stopped(102)!!))
    }

    @Test fun duplicateAndUnknownStopsDoNotRemoveAnActiveClient() {
        val sessions = EngineSessions()
        sessions.started(101)
        sessions.started(102)
        sessions.stopped(101)
        val revision = sessions.revision
        assertNull(sessions.stopped(101))
        assertNull(sessions.stopped(999))
        assertEquals(revision, sessions.revision)
        assertFalse(sessions.canRestart(revision))
    }

    @Test fun reconnectCancelsDelayedRecoveryEvenAfterItDisconnectsAgain() {
        val sessions = EngineSessions()
        sessions.started(101)
        val oldRecovery = sessions.stopped(101)!!
        assertTrue(sessions.canRestart(oldRecovery))
        sessions.started(102)
        assertFalse(sessions.canRestart(oldRecovery))
        val newRecovery = sessions.stopped(102)!!
        assertFalse(sessions.canRestart(oldRecovery))
        assertTrue(sessions.canRestart(newRecovery))
    }

    @Test fun finalRecoveryCheckProtectsAConnectionStartedAfterScheduling() {
        val sessions = EngineSessions()
        sessions.started(101)
        val recovery = sessions.stopped(101)!!
        assertTrue(sessions.canRestart(recovery))
        sessions.started(102)
        assertFalse(sessions.canRestart(recovery))
    }

    @Test fun reusedNativeThreadIdInvalidatesAnOlderRecovery() {
        val sessions = EngineSessions()
        sessions.started(101)
        val oldRecovery = sessions.stopped(101)!!
        sessions.started(101)
        assertFalse(sessions.canRestart(oldRecovery))
        assertTrue(sessions.canRestart(sessions.stopped(101)!!))
    }
}
