package com.alkaidlab.sunshine.service

/** Binder producers share one pending GL wakeup; event order is never changed. */
internal class FrameProgressMailbox {
    data class Key(val threadId: Long, val ptsUs: Long)
    data class Progress(val key: Key, val encoded: Boolean, val released: Boolean)
    private var pending = arrayListOf<Progress>()
    private var scheduled = false
    @Volatile var callbacks = 0L
        private set
    @Volatile var batches = 0L
        private set

    @Synchronized fun offer(threadId: Long, ptsUs: Long, released: Boolean): Boolean {
        callbacks++
        // A network retirement can precede its codec notification. Folding by
        // PTS could move a late codec event ahead of another frame and wrongly
        // return its input credit. Batch tasks, preserving the original FIFO.
        pending.add(Progress(Key(threadId, ptsUs), encoded = !released, released = released))
        if (scheduled) return false
        scheduled = true
        return true
    }

    @Synchronized fun drain(): Collection<Progress> {
        val result = pending
        pending = arrayListOf()
        scheduled = false
        if (result.isNotEmpty()) batches++
        return result
    }

    @Synchronized fun clear(resetCounters: Boolean = false) {
        pending.clear(); scheduled = false
        if (resetCounters) { callbacks = 0; batches = 0 }
    }
}
