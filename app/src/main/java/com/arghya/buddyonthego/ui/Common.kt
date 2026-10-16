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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arghya.buddyonthego.ServiceLocator
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
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

    /** Splits a clock time into the big part and the small AM/PM part. */
    fun parts(hour: Int, minute: Int, h12: Boolean): Pair<String, String> =
        if (h12) {
            val h = if (hour % 12 == 0) 12 else hour % 12
            Pair(String.format(Locale.US, "%d:%02d", h, minute), if (hour < 12) "AM" else "PM")
        } else Pair(String.format(Locale.US, "%02d:%02d", hour, minute), "")
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


/** Space the floating bottom bar and the status bar take, provided by the root layout. */
val LocalPad = androidx.compose.runtime.compositionLocalOf { PaddingValues(0.dp) }

@Composable
fun listPadding(): PaddingValues {
    val p = LocalPad.current
    return PaddingValues(20.dp, p.calculateTopPadding(), 20.dp, p.calculateBottomPadding())
}

@Composable
fun SectionTitle(text: String) {
    val look = LocalLook.current
    Row(Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Brush.linearGradient(listOf(look.accentA, look.accentB))))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.6.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ScreenHeader(title: String, eyebrow: String) {
    val look = LocalLook.current
    Column(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
        Text(
            eyebrow.uppercase(),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.4.sp, brush = Brush.linearGradient(listOf(look.accentA, look.accentB))),
        )
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
fun GradientText(text: String, size: Int = 44, weight: FontWeight = FontWeight.ExtraLight) {
    val look = LocalLook.current
    Text(text, style = TextStyle(fontSize = size.sp, fontWeight = weight, letterSpacing = (-1).sp, brush = Brush.linearGradient(listOf(look.accentA, look.accentB))))
}

/** Frosted-glass card: soft gradient fill and a thin light border. No blur, so scrolling stays smooth. */
@Composable
fun CardBox(modifier: Modifier = Modifier, accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val look = LocalLook.current
    val shape = RoundedCornerShape(28.dp)
    val border = if (accent) Brush.linearGradient(listOf(look.accentA.copy(alpha = 0.9f), look.accentB.copy(alpha = 0.9f)))
    else Brush.linearGradient(listOf(look.borderHi, look.borderLo))
    Column(
        modifier.fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(look.cardTop, look.cardBottom)))
            .border(if (accent) 1.5.dp else 1.dp, border, shape)
            .padding(18.dp),
        content = content,
    )
}

@Composable
fun GlowFab(icon: ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val look = LocalLook.current
    Box(
        modifier.size(62.dp)
            .shadow(18.dp, CircleShape, ambientColor = look.accentB, spotColor = look.accentA)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(look.accentA, look.accentB)))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Color(0xFF07091A), modifier = Modifier.size(28.dp)) }
}
