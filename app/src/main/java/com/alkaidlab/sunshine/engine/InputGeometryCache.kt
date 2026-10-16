package com.alkaidlab.sunshine.engine

/** Owned under InputBroker's monitor; unrelated displays do not invalidate input mapping. */
internal class InputGeometryCache {
    private var source = 0
    private var dirty = true
    fun sourceChanged(id: Int) { source = id; dirty = true }
    fun displayChanged(id: Int) { if (id == source) dirty = true }
    fun refresh(read: () -> Unit) {
        if (!dirty) return
        read()
        dirty = false
    }
}
