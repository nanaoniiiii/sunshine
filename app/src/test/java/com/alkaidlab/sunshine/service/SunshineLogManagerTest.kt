package com.alkaidlab.sunshine.service

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SunshineLogManagerTest {
    @Test fun boundedHistoryAndPublishedSnapshotsKeepTheirOrder() {
        SunshineLogManager.clear()
        repeat(600) { SunshineLogManager.log("TEST", "item-$it") }
        val snapshot = SunshineLogManager.getRecentLogs()
        assertEquals(500, snapshot.size)
        assertTrue(snapshot.first().endsWith("item-100"))
        assertTrue(snapshot.last().endsWith("item-599"))
        assertEquals(snapshot, SunshineLogManager.logsFlow.value)
        SunshineLogManager.clear()
        assertEquals(500, snapshot.size)
        assertTrue(SunshineLogManager.getRecentLogs().isEmpty())
        assertTrue(SunshineLogManager.logsFlow.value.isEmpty())
    }

    @Test fun concurrentAppendAndClearLeaveThePublishedHistoryConsistent() {
        SunshineLogManager.clear()
        val pool = Executors.newFixedThreadPool(5)
        val start = CountDownLatch(1)
        try {
            val writers = (0..3).map { writer -> pool.submit {
                start.await()
                repeat(500) { SunshineLogManager.log("TEST", "$writer:$it") }
            } }
            val clearer = pool.submit { start.await(); repeat(500) { SunshineLogManager.clear() } }
            start.countDown()
            (writers + clearer).forEach { it.get(15, TimeUnit.SECONDS) }
            val final = SunshineLogManager.getRecentLogs()
            assertTrue(final.size <= 500)
            assertEquals(final, SunshineLogManager.logsFlow.value)
            SunshineLogManager.clear()
            assertTrue(SunshineLogManager.logsFlow.value.isEmpty())
        } finally { start.countDown(); pool.shutdownNow() }
    }
}
