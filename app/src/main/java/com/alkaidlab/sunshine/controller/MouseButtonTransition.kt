package com.alkaidlab.sunshine.controller

/** The order emitted by Android's cursor input mapper for primary/secondary mouse buttons. */
enum class MouseStepAction { DOWN, MOVE, UP, HOVER_MOVE, BUTTON_PRESS, BUTTON_RELEASE }
data class MouseStep(val action: MouseStepAction, val buttons: Int, val actionButton: Int = 0)

object MouseButtonTransition {
    const val PRIMARY = 1
    const val SECONDARY = 2
    private val supported = PRIMARY or SECONDARY

    fun steps(previous: Int, current: Int): List<MouseStep> {
        val before = previous and supported
        val after = current and supported
        if (before == after) return emptyList()
        val result = mutableListOf<MouseStep>()
        var buttons = before
        for (button in listOf(PRIMARY, SECONDARY)) if (before and button != 0 && after and button == 0) {
            buttons = buttons and button.inv()
            result += MouseStep(MouseStepAction.BUTTON_RELEASE, buttons, button)
        }
        result += MouseStep(when {
            before == 0 -> MouseStepAction.DOWN
            after == 0 -> MouseStepAction.UP
            else -> MouseStepAction.MOVE
        }, after)
        for (button in listOf(PRIMARY, SECONDARY)) if (before and button == 0 && after and button != 0) {
            buttons = buttons or button
            result += MouseStep(MouseStepAction.BUTTON_PRESS, buttons, button)
        }
        if (after == 0) result += MouseStep(MouseStepAction.HOVER_MOVE, 0)
        return result
    }
}
