package com.arghya.buddyonthego.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arghya.buddyonthego.ServiceLocator
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun rememberBool(key: String, def: Boolean): Boolean {
    val s = ServiceLocator.settings
    s.tick.collectAsState().value
    return s.bool(key, def)
}

@Composable
fun rememberInt(key: String, def: Int): Int {
    val s = ServiceLocator.settings
    s.tick.collectAsState().value
    return s.int(key, def)
}

object Fmt {
    fun time(hour: Int, minute: Int, h12: Boolean): String =
        if (h12) {
            val h = if (hour % 12 == 0) 12 else hour % 12
            String.format(Locale.US, "%d:%02d %s", h, minute, if (hour < 12) "AM" else "PM")
        } else String.format(Locale.US, "%02d:%02d", hour, minute)

    fun dist(m: Double, km: Boolean): String =
        if (km) {
            if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000) else "${m.roundToInt()} m"
        } else {
            val mi = m / 1609.344
            if (mi >= 0.2) String.format(Locale.US, "%.1f mi", mi) else "${(m * 3.28084).roundToInt()} ft"
        }

    fun duration(ms: Long): String {
        val mins = (ms / 60_000).coerceAtLeast(0)
        val d = mins / 1440
        val h = (mins % 1440) / 60
        val m = mins % 60
        return buildString {
            if (d > 0) append("${d}d ")
            if (h > 0 || d > 0) append("${h}h ")
            append("${m}m")
        }
    }

    val dayLetters = listOf("M", "T", "W", "T", "F", "S", "S")
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun Stepper(label: String, value: Int, min: Int, max: Int, step: Int = 1, suffix: String = "", onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        FilledTonalIconButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Icon(Icons.Default.Remove, "Decrease $label") }
        Text("$value$suffix", Modifier.widthIn(min = 64.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        FilledTonalIconButton(onClick = { onChange((value + step).coerceAtMost(max)) }) { Icon(Icons.Default.Add, "Increase $label") }
    }
}

@Composable
fun DisabledNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initialHour: Int, initialMinute: Int, is24: Boolean, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour, initialMinute, is24)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}

@Composable
fun CardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) { Column(Modifier.padding(16.dp), content = content) }
}
