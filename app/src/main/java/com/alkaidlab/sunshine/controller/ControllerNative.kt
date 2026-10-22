package com.alkaidlab.sunshine.controller

/** Loaded in the shell UserService only; never open input devices from the app UID. */
object ControllerNative {
    external fun devices(): Array<String>
    external fun open(path: String): Int
    external fun close(fd: Int)
    external fun grab(fd: Int, enabled: Boolean): Boolean
    external fun read(fd: Int): IntArray
    external fun axis(fd: Int, code: Int): IntArray
    external fun createWake(): Int
    external fun watchInputs(): Int
    external fun wake(fd: Int)
    external fun awaitInput(fds: IntArray, wake: Int, watch: Int, timeout: Int): Int
    external fun createGamepad(id: Int): Int
    external fun writeGamepad(fd: Int, buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float): Boolean
    external fun destroyGamepad(fd: Int)
}
