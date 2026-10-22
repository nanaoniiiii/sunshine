package com.alkaidlab.sunshine.controller

/** Keeps a key's first-down route through repeats and release, even if modifiers change. */
internal class RemoteKeyRouter(private val shortcutModifierMask: Int) {
    private val captured = mutableMapOf<Int, Boolean>()

    @Synchronized
    fun route(key: Int, down: Boolean, modifiers: Int, canCapture: Boolean): Boolean {
        if (!down) return captured.remove(key) ?: false
        return captured.getOrPut(key) { canCapture && modifiers and shortcutModifierMask == 0 }
    }

    @Synchronized
    fun clear() { captured.clear() }
}
