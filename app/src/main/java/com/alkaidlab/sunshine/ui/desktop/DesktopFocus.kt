package com.alkaidlab.sunshine.ui.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

fun Modifier.desktopFocus(zone: String, onZone: (String) -> Unit, onBounds: (Rect) -> Unit): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    onFocusChanged {
        focused = it.isFocused
        if (focused) { onZone(zone); if (bounds != Rect.Zero) onBounds(bounds) }
    }.onGloballyPositioned {
        bounds = it.boundsInRoot()
        if (focused) onBounds(bounds)
    }
}
