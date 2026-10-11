package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test

class PcmAudioBufferTest {
    @Test fun overflowKeepsLatestStereoFrames() {
        val buffer = PcmAudioBuffer(8)
        buffer.write(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), 6)
        buffer.write(floatArrayOf(7f, 8f, 9f, 10f, 11f, 12f), 6)
        assertArrayEquals(floatArrayOf(5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f), buffer.read(8), 0f)
        assertEquals(4L, buffer.snapshot().second)
    }

    @Test fun oversizedWriteKeepsTheNewestSamples() {
        val buffer = PcmAudioBuffer(4)
        buffer.write(floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f), 8)
        assertArrayEquals(floatArrayOf(4f, 5f, 6f, 7f), buffer.read(4), 0f)
        assertEquals(4L, buffer.snapshot().second)
    }

    @Test fun wraparoundAndPartialReadsPreserveChannelOrder() {
        val buffer = PcmAudioBuffer(6)
        buffer.write(floatArrayOf(0f, 1f, 2f, 3f), 4)
        assertArrayEquals(floatArrayOf(0f, 1f), buffer.read(3), 0f)
        buffer.write(floatArrayOf(4f, 5f, 6f, 7f), 4)
        assertArrayEquals(floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f), buffer.read(99), 0f)
    }

    @Test fun clearingIdleCapturePreventsOldAudioOnReconnect() {
        val buffer = PcmAudioBuffer(960)
        buffer.write(FloatArray(960) { 1f }, 960)
        buffer.clear()
        assertTrue(buffer.read(960).isEmpty())
        buffer.write(floatArrayOf(2f, 3f), 2)
        assertArrayEquals(floatArrayOf(2f, 3f), buffer.read(2), 0f)
    }

    @Test fun negativeOrOddRequestsCannotSplitStereoFrames() {
        val buffer = PcmAudioBuffer(5)
        buffer.write(floatArrayOf(1f, 2f, 3f), 3)
        assertEquals(2, buffer.snapshot().first)
        assertTrue(buffer.read(-1).isEmpty())
        assertArrayEquals(floatArrayOf(1f, 2f), buffer.read(3), 0f)
    }

    @Test fun anIncompleteOpusPacketIsRetainedUntilComplete() {
        val buffer = PcmAudioBuffer(8)
        buffer.write(floatArrayOf(0f, 1f), 2)
        assertTrue(buffer.readPacket(4).isEmpty())
        assertEquals(2, buffer.snapshot().first)
        buffer.write(floatArrayOf(2f, 3f), 2)
        assertArrayEquals(floatArrayOf(0f, 1f, 2f, 3f), buffer.readPacket(4), 0f)
    }
}
