package com.alkaidlab.sunshine.controller

import kotlin.math.*

enum class RadialKind { ACTIONS, APPS }
data class RadialItem(val id: String, val label: String, val component: String? = null)
sealed class RadialEvent {
    data class Open(val kind: RadialKind) : RadialEvent()
    data class Select(val index: Int) : RadialEvent()
    data class Release(val index: Int) : RadialEvent()
}
/** Trigger latch + stick selection, independent of UI and Android. */
class RadialGesture {
    private var kind: RadialKind? = null
    private var opened = false
    private var selected = -1
    private var blockedUntilNeutral = false
    val capturesSticks: Boolean get() = kind != null
    val pending: Boolean get() = kind != null && !opened
    fun cancel() { kind = null; opened = false; selected = -1; blockedUntilNeutral = true }
    fun update(sample: ControllerSample, _now: Long, enabled: Boolean, count: Int = 8): List<RadialEvent> {
        if (!enabled) { cancel(); return emptyList() }
        if (blockedUntilNeutral) {
            if (sample.lt < .3f && sample.rt < .3f) blockedUntilNeutral = false
            return emptyList()
        }
        if (kind == null) {
            kind = when { sample.lt >= .6f -> RadialKind.ACTIONS; sample.rt >= .6f -> RadialKind.APPS; else -> null }
        }
        val active = kind ?: return emptyList()
        val held = if (active == RadialKind.ACTIONS) sample.lt > .3f else sample.rt > .3f
        if (!held) {
            val result = if (opened) listOf(RadialEvent.Release(selected)) else emptyList()
            cancel()
            blockedUntilNeutral = sample.lt >= .3f || sample.rt >= .3f
            return result
        }
        if (!opened) { opened = true; selected = -1; return listOf(RadialEvent.Open(active)) + select(sample, count) }
        return if (opened) select(sample, count) else emptyList()
    }
    private fun select(sample: ControllerSample, count: Int): List<RadialEvent> {
        val left = hypot(sample.lx, sample.ly)
        val right = hypot(sample.rx, sample.ry)
        val x = if (left >= right) sample.lx else sample.rx
        val y = if (left >= right) sample.ly else sample.ry
        val next = index(x, y, count)
        if (next == selected) return emptyList()
        selected = next
        return listOf(RadialEvent.Select(next))
    }
    companion object {
        fun index(x: Float, y: Float, count: Int): Int {
            if (count <= 0 || hypot(x, y) < .4f) return -1
            val angle = (atan2(x.toDouble(), -y.toDouble()) + 2 * PI) % (2 * PI)
            return (floor(angle / (2 * PI / count) + .5).toInt() % count)
        }
    }
}
