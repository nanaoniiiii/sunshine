package com.alkaidlab.sunshine.engine

import android.app.Application
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TouchEventStagingTest {
    @Test fun existingEventDoesNotChangeWhenScratchStorageIsReused() {
        val staging = TouchEventStaging()
        staging.append(2, 10f, 20f, .5f)
        staging.append(7, 30f, 40f, 1f)
        val first = staging.obtain(1, 2, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8))
        staging.begin()
        staging.append(9, 90f, 80f, 1f)
        val next = staging.obtain(3, 4, MotionEvent.ACTION_DOWN)
        try {
            assertEquals(2, first.pointerCount)
            assertEquals(1, first.actionIndex)
            assertEquals(7, first.getPointerId(1))
            assertEquals(30f, first.getX(1), 0f)
            assertEquals(1, next.pointerCount)
            assertEquals(9, next.getPointerId(0))
            assertEquals(90f, next.x, 0f)
        } finally { first.recycle(); next.recycle() }
    }
    @Test fun allSupportedPointersRetainTheirIdsAndCoordinates() {
        val staging = TouchEventStaging()
        repeat(32) { staging.append(it, it * 2f, it * 3f, .5f) }
        val event = staging.obtain(1, 2, MotionEvent.ACTION_POINTER_UP or (31 shl 8))
        try {
            assertEquals(32, event.pointerCount)
            assertEquals(31, event.actionIndex)
            repeat(32) { assertEquals(it, event.getPointerId(it)); assertEquals(it * 2f, event.getX(it), 0f) }
        } finally { event.recycle() }
    }
}
