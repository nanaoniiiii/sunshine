package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

/** Prepared for the audio validation phase; not part of this run's executed checks. */
class PcmAudioResizeTest {
    @Test fun expandedWrappedBufferKeepsStereoOrder() {
        val queue = PcmAudioBuffer(8)
        queue.write(FloatArray(8) { it.toFloat() }, 8)
        queue.read(6)
        queue.write(floatArrayOf(8f, 9f, 10f, 11f), 4)
        queue.setCapacity(16)
        assertArrayEquals(floatArrayOf(6f, 7f, 8f, 9f, 10f, 11f), queue.read(16), 0f)
    }
    @Test fun smallerBufferKeepsNewestCompleteFramesAndAccountsForDrops() {
        val queue = PcmAudioBuffer(8)
        queue.write(FloatArray(8) { it.toFloat() }, 8)
        queue.setCapacity(5)
        assertEquals(4, queue.capacityFloats)
        assertArrayEquals(floatArrayOf(4f, 5f, 6f, 7f), queue.read(8), 0f)
        assertEquals(4L, queue.snapshot().second)
    }
    @Test fun equalCapacityDoesNotConsumeSamples() {
        val queue = PcmAudioBuffer(4)
        queue.write(floatArrayOf(1f, 2f), 2)
        queue.setCapacity(4)
        assertArrayEquals(floatArrayOf(1f, 2f), queue.read(2), 0f)
    }
}
