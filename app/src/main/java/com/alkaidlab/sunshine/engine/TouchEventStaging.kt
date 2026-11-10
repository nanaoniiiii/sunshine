package com.alkaidlab.sunshine.engine

import android.view.InputDevice
import android.view.MotionEvent

/** Reuses scratch storage; MotionEvent copies the active pointers on obtain. */
internal class TouchEventStaging {
    private val properties = Array(32) { MotionEvent.PointerProperties() }
    private val coordinates = Array(32) { MotionEvent.PointerCoords() }
    var count = 0
        private set

    fun begin() { count = 0 }
    fun append(id: Int, x: Float, y: Float, pressure: Float) {
        check(count < properties.size)
        properties[count].apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        coordinates[count].apply {
            clear(); this.x = x; this.y = y
            this.pressure = pressure.coerceIn(.01f, 1f); size = .05f
        }
        count++
    }
    fun obtain(downTime: Long, eventTime: Long, action: Int): MotionEvent {
        check(count > 0)
        return MotionEvent.obtain(downTime, eventTime, action, count, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
}
