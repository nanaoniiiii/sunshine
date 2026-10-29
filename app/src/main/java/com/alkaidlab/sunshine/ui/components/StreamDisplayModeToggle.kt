package com.alkaidlab.sunshine.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.alkaidlab.sunshine.service.StreamDisplayMode

@Composable
fun StreamDisplayModeToggle(mode: StreamDisplayMode, enabled: Boolean, onSelect: (StreamDisplayMode) -> Unit) {
    Surface(shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.selectableGroup().padding(3.dp)) {
            for ((choice, label) in listOf(StreamDisplayMode.DEFAULT to "默认", StreamDisplayMode.FULLSCREEN to "全屏")) {
                val selected = mode == choice
                Surface(shape = MaterialTheme.shapes.extraSmall,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.heightIn(min = 42.dp).selectable(selected, enabled, Role.RadioButton) { onSelect(choice) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
