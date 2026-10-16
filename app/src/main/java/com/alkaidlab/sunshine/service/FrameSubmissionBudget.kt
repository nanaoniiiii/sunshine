package com.alkaidlab.sunshine.service

/** GL-thread-owned credits covering encoder input, encoded queue and network send. */
class FrameSubmissionBudget(private val limit: Int = 4, private val timeoutNs: Long = 350_000_000L) {
    // false: submitted to codec; true: encoded and awaiting packet retirement.
    private val pending = java.util.TreeMap<Long, Boolean>()
    @Volatile var pendingCount = 0
        private set
    @Volatile var expired = 0L
        private set
    @Volatile var encoderSkipped = 0L
        private set

    fun nextAvailableNs(nowNs: Long): Long {
        // MediaCodec reports microseconds; match its PTS precision exactly.
        val iterator = pending.keys.iterator()
        while (iterator.hasNext()) if (nowNs - iterator.next() * 1000L >= timeoutNs) {
            iterator.remove(); expired++
        }
        pendingCount = pending.size
        return if (pending.size < limit) 0L else pending.firstKey() * 1000L + timeoutNs
    }

    fun submitted(timestampNs: Long) {
        val ptsUs = timestampNs / 1000L
        pending.putIfAbsent(ptsUs, false)
        pendingCount = pending.size
    }

    fun complete(presentationTimeUs: Long): Boolean {
        val removed = pending.remove(presentationTimeUs) != null
        pendingCount = pending.size
        return removed
    }

    fun encoded(presentationTimeUs: Long): Boolean {
        if (pending.containsKey(presentationTimeUs)) pending[presentationTimeUs] = true
        // No B-frames are configured. Once a later PTS emerges, older inputs
        // without output were skipped by the codec. Encoded packets retain
        // their credits until actual send/discard, including out-of-order ACKs.
        val skipped = pending.headMap(presentationTimeUs).filterValues { !it }.keys.toList()
        skipped.forEach { pending.remove(it) }
        encoderSkipped += skipped.size
        pendingCount = pending.size
        return skipped.isNotEmpty()
    }
}
