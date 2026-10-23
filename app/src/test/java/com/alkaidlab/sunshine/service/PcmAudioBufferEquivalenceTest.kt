package com.alkaidlab.sunshine.service

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.Random

class PcmAudioBufferEquivalenceTest {
    @Test fun mixedWrappedOverflowAndPacketOperationsMatchAStereoQueue() {
        val random = Random(0x51A0)
        for (requestedCapacity in listOf(0, 3, 6, 17, 64, 960, 3840)) {
            val buffer = PcmAudioBuffer(requestedCapacity)
            val expected = ArrayDeque<Float>()
            var dropped = 0L
            repeat(1500) { step ->
                when (random.nextInt(5)) {
                    0, 1 -> {
                        val source = FloatArray(random.nextInt(2200)) { step * .01f + it }
                        val count = random.nextInt(source.size + 21) - 10
                        val available = minOf(count, source.size).coerceAtLeast(0) / 2 * 2
                        repeat(available) { expected.addLast(source[it]) }
                        while (expected.size > buffer.capacityFloats) { expected.removeFirst(); dropped++ }
                        buffer.write(source, count)
                    }
                    2, 3 -> {
                        val count = random.nextInt(2300) - 10
                        val packet = random.nextBoolean()
                        val requested = count.coerceAtLeast(0) / 2 * 2
                        val length = if (packet && (requested > buffer.capacityFloats || requested > expected.size)) 0
                            else minOf(requested, expected.size)
                        val result = FloatArray(length) { expected.removeFirst() }
                        assertArrayEquals(result, if (packet) buffer.readPacket(count) else buffer.read(count), 0f)
                    }
                    else -> { expected.clear(); buffer.clear() }
                }
                assertEquals(expected.size, buffer.snapshot().first)
                assertEquals(dropped, buffer.snapshot().second)
            }
            assertArrayEquals(expected.toFloatArray(), buffer.read(buffer.capacityFloats), 0f)
        }
    }
}
