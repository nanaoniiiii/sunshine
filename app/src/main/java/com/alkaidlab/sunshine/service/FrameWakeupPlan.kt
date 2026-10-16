package com.alkaidlab.sunshine.service

/** GL-thread-owned deadline bookkeeping; retain an already earlier wakeup. */
internal class FrameWakeupPlan {
    enum class Change { KEEP, POST, CANCEL }
    private var pending: Long? = null
    @Volatile var posts = 0L
        private set
    @Volatile var retained = 0L
        private set
    fun update(deadline: Long?): Change {
        val previous = pending
        if (deadline == null) {
            pending = null
            return if (previous == null) Change.KEEP else Change.CANCEL
        }
        if (previous != null && previous <= deadline) { retained++; return Change.KEEP }
        pending = deadline; posts++
        return Change.POST
    }
    fun consumed() { pending = null }
    fun reset() { pending = null; posts = 0; retained = 0 }
}
