package com.alkaidlab.sunshine.controller

/** Linux ABS codes exposed by the current evdev node. */
data class ControllerAxisLayout(val rightX: Int, val rightY: Int, val lt: Int, val rt: Int) {
    companion object {
        fun detect(codes: Set<Int>): ControllerAxisLayout {
            val rightX = if (3 in codes) 3 else if (2 in codes) 2 else -1
            val rightY = if (4 in codes) 4 else if (5 in codes) 5 else -1
            // Samsung's Xbox Bluetooth driver: BRAKE=LT, GAS=RT, Z/RZ=right stick.
            val lt = if (10 in codes) 10 else if (3 in codes && 2 in codes) 2 else -1
            val rt = if (9 in codes) 9 else if (4 in codes && 5 in codes) 5 else -1
            return ControllerAxisLayout(rightX, rightY, lt, rt)
        }
    }
}
