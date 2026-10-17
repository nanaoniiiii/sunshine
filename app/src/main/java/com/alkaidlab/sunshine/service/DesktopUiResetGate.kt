package com.alkaidlab.sunshine.service

/** Rebuild Samsung's stale freeform input root only after a completed desktop exit. */
internal class DesktopUiResetGate {
    private var sawDesktop = false
    fun update(active: Boolean, busy: Boolean, mode: String): Boolean {
        if (active && mode == "desktop") sawDesktop = true
        if (active || busy || !sawDesktop) return false
        sawDesktop = false
        return true
    }
}
