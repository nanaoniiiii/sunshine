package com.alkaidlab.sunshine.service

/** A reversible logical-size override for one external DeX display, never display 0. */
internal class DexResolutionOverride(private val displayId: Int, private val backend: Backend) : AutoCloseable {
    interface Backend {
        fun snapshot(): FullscreenDisplay.Snapshot
        fun rotation(): Int
        fun setSize(size: FullscreenDisplay.Size?)
        fun awaitSize(size: FullscreenDisplay.Size): Boolean
        fun sameDisplay(): Boolean
    }

    init { require(displayId > 0) { "DeX 分辨率调整只适用于独立第二屏" } }
    private var before: FullscreenDisplay.Snapshot? = null
    private var changed = false

    fun apply(width: Int, height: Int) {
        require(width in 240..8192 && height in 240..8192)
        check(before == null)
        val request = FullscreenDisplay.Size(width, height)
        before = backend.snapshot()
        try {
            // wm accepts the display's natural orientation; the requested canvas is logical.
            changed = true // Roll back even if the system changes size then reports failure.
            backend.setSize(FullscreenDisplay.baseSize(request, backend.rotation()))
            if (!backend.awaitSize(request)) {
                check(backend.sameDisplay()) { "DeX 显示已断开" }
                backend.setSize(FullscreenDisplay.baseSize(request, backend.rotation()))
                check(backend.awaitSize(request)) { "系统未按 ${width} × ${height} 应用 DeX 桌面分辨率" }
            }
        } catch (error: Exception) {
            try { close() } catch (restore: Exception) { error.addSuppressed(restore) }
            throw error
        }
    }

    override fun close() {
        if (!changed) return
        if (backend.sameDisplay()) backend.setSize(checkNotNull(before).override)
        changed = false
    }
}
