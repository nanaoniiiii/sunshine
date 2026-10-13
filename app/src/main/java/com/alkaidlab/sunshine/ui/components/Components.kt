package com.alkaidlab.sunshine.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.alkaidlab.sunshine.ui.theme.*

val PagePadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp)

@Composable
fun PageHeader(title: String, subtitle: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        action()
    }
}

@Composable
fun SectionCard(title: String? = null, icon: ImageVector? = null,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = CardBg, border = BorderStroke(1.dp, CardBorder)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (title != null) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (icon != null) Icon(icon, null, tint = PrimaryCyan, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            content()
        }
    }
}

@Composable
fun StatusTag(text: String, color: Color = PrimaryCyan) {
    Surface(color = color.copy(alpha = .10f), shape = MaterialTheme.shapes.small) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = PrimaryCyan, modifier = Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun SettingSwitch(title: String, description: String, checked: Boolean, enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked, onCheckedChange, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
fun PinField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {}) {
    OutlinedTextField(value, { onValueChange(it.filter { c -> c in '0'..'9' }.take(4)) },
        modifier = modifier, label = { Text("4 位配对码") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (value.length == 4) onSubmit() }),
        shape = MaterialTheme.shapes.small)
}

/** Feedback changes drawing only; layout and actions never wait for the animation. */
@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f,
        tween(120, easing = CubicBezierEasing(.23f, 1f, .32f, 1f)), label = "buttonPress")
    Button(onClick, modifier.graphicsLayer { scaleX = scale; scaleY = scale }, enabled = enabled,
        interactionSource = interaction, content = content)
}
