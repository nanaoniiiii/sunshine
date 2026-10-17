package com.alkaidlab.sunshine.service

/** Only a user-selected session changes the device display; joining sessions do not. */
object FullscreenDisplay {
    data class Size(val width: Int, val height: Int) {
        override fun toString() = "${width}x$height"
    }
    data class Snapshot(val override: Size?, val rotation: Int,
        val physical: Size = Size(1440, 3168), val autoRotate: Boolean = false, val userRotation: Int = rotation,
        val fixedRotation: Boolean = false)
    data class Result(val applied: Boolean, val message: String)
    interface Backend {
        fun snapshot(): Snapshot?
        fun rotation(): Int
        fun setRotation(rotation: Int, autoRotate: Boolean = false): Boolean
        fun setFixedRotation(enabled: Boolean): Boolean
        fun setOverride(size: Size?): Boolean
        fun awaitDisplay(size: Size): Boolean
        fun syncCapture(): Boolean
    }
    fun baseSize(request: Size, rotation: Int): Size =
        if (rotation % 2 == 1) Size(request.height, request.width) else request
    fun targetRotation(request: Size, physical: Size, current: Int): Int {
        val targetOdd = (request.width > request.height) != (physical.width > physical.height)
        return if ((current % 2 == 1) == targetOdd) current else if (targetOdd) 1 else 0
    }

    /** Android renamed these wm subcommands; match whole help tokens, not suffixes. */
    fun rotationCommand(help: String, fixed: Boolean = false): String? {
        val names = if (fixed) listOf("fixed-to-user-rotation", "set-fix-to-user-rotation")
            else listOf("user-rotation", "set-user-rotation")
        return names.firstOrNull { Regex("(?m)^\\s*${Regex.escape(it)}(?:\\s|$)").containsMatchIn(help) }
    }

    fun parseOverride(output: String): Snapshot? {
        val physical = Regex("Physical size: (\\d+)x(\\d+)").find(output) ?: return null
        val match = Regex("Override size: (\\d+)x(\\d+)").find(output)
        return Snapshot(match?.let { Size(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }, 0,
            Size(physical.groupValues[1].toInt(), physical.groupValues[2].toInt()))
    }
    fun parseFixedRotation(output: String): Boolean? {
        val display = Regex("(?m)^\\s*Display: mDisplayId=0\\b[\\s\\S]*?(?=\\n\\s*Display: mDisplayId=|\\z)")
            .find(output)?.value ?: return null
        return when (Regex("mFixedToUserRotation=(true|false)\\b").find(display)?.groupValues?.get(1)) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
    fun restore(before: Snapshot, backend: Backend): Boolean = try {
        val size = backend.setOverride(before.override)
        val fixed = backend.setFixedRotation(before.fixedRotation)
        val rotation = backend.setRotation(before.userRotation, before.autoRotate)
        size && fixed && rotation && backend.syncCapture()
    } catch (_: Exception) { false }

    /** Roll back even a shell failure: an OEM may change the display before reporting failure. */
    fun apply(request: Size, backend: Backend, isCurrent: () -> Boolean): Result {
        if (request.width !in 1..8192 || request.height !in 1..8192)
            return Result(false, "该连接的分辨率无法用于本机显示")
        if (!isCurrent()) return Result(false, "该连接已结束，请重新选择")
        val before = try { backend.snapshot() } catch (_: Exception) { null }
            ?: return Result(false, "无法读取本机分辨率，未进行修改")
        try {
            if (!isCurrent()) return Result(false, "该连接已结束，请重新选择")
            val rotation = targetRotation(request, before.physical, before.rotation)
            // A rotation lock alone is ignored by portrait-only activities and launchers.
            check(backend.setFixedRotation(true))
            check(backend.setRotation(rotation))
            check(backend.setOverride(baseSize(request, rotation)))
            if (!backend.awaitDisplay(request)) {
                // An OEM can rotate the display when its natural dimensions change.
                check(isCurrent())
                check(backend.setOverride(baseSize(request, backend.rotation())))
                check(backend.awaitDisplay(request))
            }
            check(isCurrent())
            check(backend.syncCapture())
            check(isCurrent())
            return Result(true, "已按 ${request.width} × ${request.height} 全屏显示")
        } catch (_: Exception) {
            val restored = restore(before, backend)
            return Result(false, if (restored) "全屏切换未完成，已恢复原分辨率；现有连接保持"
                else "全屏切换未完成，恢复原分辨率失败，请检查 Shizuku 和系统显示设置")
        }
    }
}
