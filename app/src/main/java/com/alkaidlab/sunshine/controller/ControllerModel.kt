package com.alkaidlab.sunshine.controller

import kotlin.math.abs

enum class ControllerMode(val title: String) { NAVIGATION("导航"), MOUSE("鼠标"), NATIVE("游戏原生") }
enum class ControllerAction(val title: String) {
    CONFIRM("确定 / 鼠标点击"), BACK("系统返回"), HOME("系统主页"), RECENTS("最近任务"),
    NOTIFICATIONS("下拉通知栏"), MENU("菜单"), POWER("系统电源菜单"),
    UP("上"), DOWN("下"), LEFT("左"), RIGHT("右"),
    PREVIOUS_MENU("上级菜单：向左切换"), NEXT_MENU("上级菜单：向右切换"),
    MOUSE("切换鼠标 / 导航"), NATIVE("切换游戏原生 / 系统映射"), NAVIGATION("导航模式"), NONE("不执行")
}

/** GameStream/XInput button masks. Physical evdev is normalized to the same layout. */
object ControllerButtons {
    val tokens = linkedMapOf(1 to "UP", 2 to "DOWN", 4 to "LEFT", 8 to "RIGHT", 16 to "START", 32 to "SELECT",
        64 to "L3", 128 to "R3", 256 to "L1", 512 to "R1", 1024 to "HOME", 4096 to "A", 8192 to "B", 16384 to "X", 32768 to "Y")
    val defaults = mapOf("A" to ControllerAction.CONFIRM, "B" to ControllerAction.BACK,
        "X" to ControllerAction.RECENTS, "Y" to ControllerAction.NOTIFICATIONS,
        "START" to ControllerAction.MENU, "SELECT" to ControllerAction.BACK, "HOME" to ControllerAction.HOME,
        "L3" to ControllerAction.MOUSE, "R3" to ControllerAction.NATIVE, "L1" to ControllerAction.PREVIOUS_MENU, "R1" to ControllerAction.NEXT_MENU,
        "UP" to ControllerAction.UP, "DOWN" to ControllerAction.DOWN, "LEFT" to ControllerAction.LEFT, "RIGHT" to ControllerAction.RIGHT,
        "POWER" to ControllerAction.POWER)
    fun action(token: String, overrides: Map<String, ControllerAction>) = overrides[token] ?: defaults[token] ?: ControllerAction.NONE
}

data class ControllerSample(val buttons: Int = 0, val lx: Float = 0f, val ly: Float = 0f,
    val rx: Float = 0f, val ry: Float = 0f, val lt: Float = 0f, val rt: Float = 0f)

/** Pure input state: no Android dependency. Each controller has its own mode and escape timer. */
class ControllerReducer {
    var mode = ControllerMode.NAVIGATION; private set
    var sample = ControllerSample(); private set
    val activeButtons: Int get() = sample.buttons and blockedButtons.inv()
    private var chordAt: Long? = null
    private var chordFired = false
    private var repeatToken: String? = null
    private var repeatAt = 0L
    private var blockedButtons = 0
    var sticksSuspended = false; private set
    fun suspendSticks() { sticksSuspended = true; repeatToken = null }

    fun setMode(value: ControllerMode) {
        mode = value
        blockedButtons = sample.buttons
        repeatToken = null
    }

    fun update(next: ControllerSample, now: Long): List<String> {
        val previous = sample
        sample = next
        if (maxOf(abs(next.lx), abs(next.ly), abs(next.rx), abs(next.ry)) < .25f) sticksSuspended = false
        blockedButtons = blockedButtons and next.buttons
        val chord = next.buttons and 48 == 48
        if (chord) { if (chordAt == null) chordAt = now } else { chordAt = null; chordFired = false }
        val pressed = next.buttons and previous.buttons.inv() and blockedButtons.inv()
        if (mode == ControllerMode.NATIVE) return buildList {
            if (pressed and 1024 != 0) add("SYS_HOME")
            if (pressed and 128 != 0) add("SYS_NAVIGATION")
            val active = next.buttons and blockedButtons.inv()
            if (active and 16 != 0) {
                val startPressed = pressed and 16 != 0
                if (active and 8192 != 0 && (startPressed || pressed and 8192 != 0)) add("SYS_BACK")
                if (active and 16384 != 0 && (startPressed || pressed and 16384 != 0)) add("SYS_RECENTS")
                if (active and 32768 != 0 && (startPressed || pressed and 32768 != 0)) add("SYS_NOTIFICATIONS")
            }
        }
        return ControllerButtons.tokens.filter { (mask, _) -> pressed and mask != 0 && !(chord && mask and 48 != 0) }.values.toList()
    }

    fun nativeSample(): ControllerSample {
        var buttons = sample.buttons and blockedButtons.inv() and (1024 or 128).inv()
        if (buttons and 16 != 0) buttons = buttons and (8192 or 16384 or 32768).inv()
        return if (sticksSuspended) sample.copy(buttons = buttons, lx = 0f, ly = 0f, rx = 0f, ry = 0f) else sample.copy(buttons = buttons)
    }

    fun needsTick(): Boolean = (chordAt != null && !chordFired) || (mode != ControllerMode.NATIVE && !sticksSuspended &&
        (sample.buttons and 15 != 0 || maxOf(abs(sample.lx), abs(sample.ly), abs(sample.rx), abs(sample.ry)) > .18f))

    fun tick(now: Long): List<String> {
        val started = chordAt
        if (started != null && !chordFired && now - started >= 900) {
            chordFired = true
            return listOf("ESCAPE")
        }
        if (mode == ControllerMode.NATIVE) return emptyList()
        if (sticksSuspended) return emptyList()
        val stick = if (mode == ControllerMode.NAVIGATION) direction(sample.lx, sample.ly, repeatToken != null) else null
        val dpad = ControllerButtons.tokens.entries.firstOrNull { it.key <= 8 && sample.buttons and it.key != 0 }?.value
        val token = dpad ?: stick
        if (token != repeatToken) {
            repeatToken = token; repeatAt = now + 380
            // D-pad has already emitted its initial edge in update().
            return if (token != null && dpad == null) listOf(token) else emptyList()
        }
        if (token != null && now >= repeatAt) { repeatAt = now + 95; return listOf(token) }
        return emptyList()
    }

    companion object {
        fun direction(x: Float, y: Float, held: Boolean = false): String? {
            if (maxOf(abs(x), abs(y)) < if (held) .23f else .4f) return null
            return if (abs(x) > abs(y)) { if (x < 0) "LEFT" else "RIGHT" } else if (y < 0) "UP" else "DOWN"
        }
    }
}
