package com.alkaidlab.sunshine.service

/** Native thread identities are authoritative; EGL eviction is not a disconnect. */
internal class EngineSessions {
    private val threads = mutableSetOf<Long>()
    var revision = 0L
        private set

    fun started(threadId: Long) {
        threads.add(threadId)
        revision++
    }

    fun stopped(threadId: Long): Long? {
        if (!threads.remove(threadId)) return null
        return ++revision
    }

    fun canRestart(expectedRevision: Long): Boolean =
        threads.isEmpty() && (expectedRevision == revision || expectedRevision == MANUAL_RESTART)

    companion object {
        const val MANUAL_RESTART = -1L
    }
}
