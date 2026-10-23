package com.alkaidlab.sunshine.service

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Bounded stereo PCM ring. Overflow discards oldest complete sample frames. */
class PcmAudioBuffer(capacityFloats: Int) {
    private val samples = FloatArray(capacityFloats.coerceAtLeast(2) / 2 * 2)
    val capacityFloats: Int get() = samples.size
    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()
    private var generation = 0L
    private var head = 0
    private var size = 0
    private var droppedFloats = 0L

    fun write(source: FloatArray, count: Int) = lock.withLock {
        val available = minOf(count, source.size).coerceAtLeast(0) / 2 * 2
        val retained = minOf(available, samples.size)
        val overflow = (size + retained - samples.size).coerceAtLeast(0)
        droppedFloats += overflow + available - retained
        head = (head + overflow) % samples.size
        size -= overflow
        val offset = available - retained
        // Bulk copy instead of a per-sample modulo store: identical element
        // order, only fewer index computations on the audio hot path.
        copyRingIn(source, offset, retained)
        size += retained
        dataAvailable.signalAll()
    }

    /** Copies [length] floats from [source] at [srcOffset] into the ring tail. */
    private fun copyRingIn(source: FloatArray, srcOffset: Int, length: Int) {
        if (length <= 0) return
        val start = (head + size) % samples.size
        val firstLen = minOf(length, samples.size - start)
        System.arraycopy(source, srcOffset, samples, start, firstLen)
        val rest = length - firstLen
        if (rest > 0) System.arraycopy(source, srcOffset + firstLen, samples, 0, rest)
    }

    fun read(count: Int): FloatArray = lock.withLock { readLocked(count) }

    private fun readLocked(count: Int): FloatArray {
        val length = minOf(count.coerceAtLeast(0), size) / 2 * 2
        val result = FloatArray(length)
        // Bulk copy instead of a per-sample modulo load: identical channel order.
        val firstLen = minOf(length, samples.size - head)
        System.arraycopy(samples, head, result, 0, firstLen)
        val rest = length - firstLen
        if (rest > 0) System.arraycopy(samples, 0, result, firstLen, rest)
        head = (head + length) % samples.size
        size -= length
        return result
    }

    /** Wait without polling; native Opus encoding requires a complete packet. Clear cancels the wait. */
    fun readPacket(count: Int, timeoutMs: Long = 0L): FloatArray = lock.withLock {
        val requested = count.coerceAtLeast(0) / 2 * 2
        if (requested > samples.size) return@withLock floatArrayOf()
        val epoch = generation
        var remainingNs = timeoutMs.coerceIn(0L, 110L) * 1_000_000L
        while (size < requested && remainingNs > 0L && generation == epoch) {
            remainingNs = dataAvailable.awaitNanos(remainingNs)
        }
        if (generation == epoch && size >= requested) readLocked(requested) else floatArrayOf()
    }

    fun clear() = lock.withLock {
        head = 0
        size = 0
        generation++
        dataAvailable.signalAll()
    }

    fun snapshot(): Pair<Int, Long> = lock.withLock { size to droppedFloats }
}
