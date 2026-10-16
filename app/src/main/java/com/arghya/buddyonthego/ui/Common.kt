package com.arghya.buddyonthego.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arghya.buddyonthego.ServiceLocator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

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

    /** Minutes from midnight to text. */
    fun minutes(total: Int, h12: Boolean): String = time(total / 60, total % 60, h12)

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

    fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h < 5 -> "Late night"
            h < 12 -> "Good morning"
            h < 17 -> "Good afternoon"
            h < 21 -> "Good evening"
            else -> "Good night"
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Interaction helpers
// ---------------------------------------------------------------------------------------------

/** Springy "press down" feel for anything tappable. */
fun Modifier.pressScale(source: InteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Scrolls a focused text field above the keyboard once the keyboard has finished opening. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bringIntoViewOnFocus(): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    this.bringIntoViewRequester(requester).onFocusEvent { state ->
        if (state.isFocused) scope.launch { delay(350); requester.bringIntoView() }
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons, chips, rows
// ---------------------------------------------------------------------------------------------

enum class BtnStyle { Primary, Tonal, Outline, Text, Danger }

/** Large, easy-to-hit button used everywhere. 56 dp tall by default. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 56.dp,
) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(height / 2)
    val fg: Color = when (style) {
        BtnStyle.Primary -> look.onBtn
        BtnStyle.Tonal -> scheme.onSurface
        BtnStyle.Outline -> scheme.onSurface
        BtnStyle.Text -> scheme.primary
        BtnStyle.Danger -> look.danger
    }
    val fill: Brush? = when (style) {
        BtnStyle.Primary -> Brush.horizontalGradient(listOf(look.btnA, look.btnB))
        BtnStyle.Tonal -> Brush.horizontalGradient(listOf(scheme.surfaceVariant, scheme.surfaceVariant))
        else -> null
    }
    var m = modifier
        .heightIn(min = height)
        .pressScale(source)
        .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
        .clip(shape)
    if (fill != null) m = m.background(fill)
    if (style == BtnStyle.Outline) m = m.border(1.5.dp, scheme.outline, shape)
    if (style == BtnStyle.Danger) m = m.border(1.5.dp, look.danger.copy(alpha = 0.6f), shape)
    m = m.clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = Role.Button) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }.padding(horizontal = 22.dp, vertical = 12.dp)
    Row(m, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** A pill you can switch on and off. 48 dp tall. */
@Composable
fun BuddyChip(selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(24.dp)
    var m = modifier.heightIn(min = 48.dp).pressScale(source, 0.95f).clip(shape)
    m = if (selected) m.background(Brush.horizontalGradient(listOf(look.btnA, look.btnB)))
    else m.background(scheme.onSurface.copy(alpha = 0.06f)).border(1.dp, scheme.outline.copy(alpha = 0.7f), shape)
    m = m.clickable(interactionSource = source, indication = LocalIndication.current, role = Role.RadioButton) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }.padding(horizontal = 20.dp, vertical = 10.dp)
    Box(m, contentAlignment = Alignment.Center) {
        Text(
            label, fontSize = 17.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) look.onBtn else scheme.onSurface, maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

/** Seven round day buttons that share the width evenly. daysMask bit 0 = Monday. */
@Composable
fun DayPicker(mask: Int, onChange: (Int) -> Unit) {
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Fmt.dayLetters.forEachIndexed { i, d ->
            val on = mask and (1 shl i) != 0
            Box(
                Modifier.weight(1f).aspectRatio(1f).clip(CircleShape)
                    .then(
                        if (on) Modifier.background(Brush.linearGradient(listOf(look.btnA, look.btnB)))
                        else Modifier.background(scheme.onSurface.copy(alpha = 0.06f)).border(1.dp, scheme.outline.copy(alpha = 0.7f), CircleShape),
                    )
                    .clickable(role = Role.Checkbox) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onChange(mask xor (1 shl i))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(d, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (on) look.onBtn else scheme.onSurface, maxLines = 1)
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun Stepper(label: String, value: Int, min: Int, max: Int, step: Int = 1, suffix: String = "", onChange: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
        FilledTonalIconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onChange((value - step).coerceAtLeast(min)) }, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Default.Remove, "Decrease $label")
        }
        Text("$value$suffix", Modifier.widthIn(min = 76.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1)
        FilledTonalIconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onChange((value + step).coerceAtMost(max)) }, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Default.Add, "Increase $label")
        }
    }
}

@Composable
fun DisabledNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
}

/** Text box that scrolls itself above the keyboard. Use this instead of a bare OutlinedTextField. */
@Composable
fun BuddyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    supportingText: String? = null,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().bringIntoViewOnFocus(),
        textStyle = MaterialTheme.typography.bodyLarge,
        label = { Text(label) },
        placeholder = if (placeholder != null) ({ Text(placeholder) }) else null,
        supportingText = if (supportingText != null) ({ Text(supportingText) }) else null,
        singleLine = singleLine, minLines = minLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = RoundedCornerShape(16.dp),
    )
}

/** Clock text with the AM/PM label in the same line, so the baseline always matches (no broken alignment on any phone). */
@Composable
fun ClockText(hour: Int, minute: Int, h12: Boolean, mainSize: TextUnit = 48.sp, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    val (main, ap) = Fmt.parts(hour, minute, h12)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontSize = mainSize, fontWeight = FontWeight.ExtraLight, letterSpacing = (-1).sp)) { append(main) }
            if (ap.isNotEmpty()) withStyle(SpanStyle(fontSize = mainSize * 0.38f, fontWeight = FontWeight.SemiBold)) { append(" $ap") }
        },
        color = color, maxLines = 1, softWrap = false, modifier = modifier,
    )
}

// ---------------------------------------------------------------------------------------------
// Scrolling time picker (a drum you swipe, instead of a round clock face)
// ---------------------------------------------------------------------------------------------

/**
 * One column of a swipe-to-choose drum. [selected] is only the starting value; the wheel reports changes through [onSelected].
 * With [wrap] the list loops (hours, minutes).
 */
@Composable
fun WheelPicker(
    labels: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    wrap: Boolean = true,
    itemHeight: Dp = 56.dp,
    visibleCount: Int = 5,
) {
    val n = labels.size
    val repeat = if (wrap) 400 else 1
    val total = n * repeat
    val startIndex = if (wrap) (repeat / 2) * n + selected else selected
    val state = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val haptic = LocalHapticFeedback.current
    val look = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val itemPx = with(LocalDensity.current) { itemHeight.toPx() }
    val currentOnSelected by rememberUpdatedState(onSelected)

    val centerIndex by remember {
        derivedStateOf {
            (state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0).coerceIn(0, total - 1)
        }
    }

    // Tell the caller (and tick) whenever a new value reaches the centre.
    LaunchedEffect(Unit) {
        snapshotFlow { centerIndex }.drop(1).collect { idx ->
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnSelected(idx % n)
        }
    }
    // When the finger lifts and the drum stops between two rows, ease it onto the nearest row.
    LaunchedEffect(Unit) {
        snapshotFlow { state.isScrollInProgress }.filter { !it }.collect {
            if (state.firstVisibleItemScrollOffset != 0) state.animateScrollToItem(centerIndex)
        }
    }

    Box(modifier.height(itemHeight * visibleCount), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth().height(itemHeight).clip(RoundedCornerShape(18.dp))
                .background(Brush.horizontalGradient(listOf(look.accentA.copy(alpha = 0.16f), look.accentB.copy(alpha = 0.16f)))),
        )
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(vertical = itemHeight * (visibleCount / 2)),
            modifier = Modifier.fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 0.3f to Color.Black, 0.7f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            items(total) { i ->
                val dist = abs(i - centerIndex)
                Box(
                    Modifier.fillMaxWidth().height(itemHeight).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { scope.launch { state.animateScrollToItem(i) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        labels[i % n],
                        fontSize = when (dist) { 0 -> 36.sp; 1 -> 26.sp; else -> 20.sp },
                        fontWeight = if (dist == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (dist == 0) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = if (dist == 1) 0.75f else 0.4f),
                        maxLines = 1, softWrap = false,
                    )
                }
            }
        }
    }
}

/** Hour : minute (and AM/PM) drums side by side. Reports 24-hour values. */
@Composable
fun TimeWheel(hour: Int, minute: Int, is24: Boolean, onChange: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    val hourLabels = remember(is24) { if (is24) List(24) { String.format(Locale.US, "%02d", it) } else List(12) { "${it + 1}" } }
    val minuteLabels = remember { List(60) { String.format(Locale.US, "%02d", it) } }
    val ampmLabels = remember { listOf("AM", "PM") }
    var hIdx by remember { mutableIntStateOf(if (is24) hour else (if (hour % 12 == 0) 12 else hour % 12) - 1) }
    var mIdx by remember { mutableIntStateOf(minute) }
    var pm by remember { mutableIntStateOf(if (hour >= 12) 1 else 0) }
    val currentOnChange by rememberUpdatedState(onChange)

    fun push() {
        val h24 = if (is24) hIdx else ((hIdx + 1) % 12) + if (pm == 1) 12 else 0
        currentOnChange(h24, mIdx)
    }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        WheelPicker(hourLabels, hIdx, { hIdx = it; push() }, Modifier.weight(1f))
        Text(":", fontSize = 34.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(horizontal = 2.dp))
        WheelPicker(minuteLabels, mIdx, { mIdx = it; push() }, Modifier.weight(1f))
        if (!is24) {
            Spacer(Modifier.width(8.dp))
            WheelPicker(ampmLabels, pm, { pm = it; push() }, Modifier.weight(1f), wrap = false)
        }
    }
}

/** Pop-up with the swipe drums. [is24] is true for a 24-hour clock. */
@Composable
fun TimeDialog(
    initialHour: Int, initialMinute: Int, is24: Boolean,
    onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit, title: String = "Set time",
) {
    var h by remember { mutableIntStateOf(initialHour) }
    var m by remember { mutableIntStateOf(initialMinute) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                TimeWheel(initialHour, initialMinute, is24, { hh, mm -> h = hh; m = mm })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("Cancel", onDismiss, Modifier.weight(1f), BtnStyle.Outline)
                    BigButton("OK", { onConfirm(h, m) }, Modifier.weight(1f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Layout helpers
// ---------------------------------------------------------------------------------------------

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
    Row(Modifier.padding(start = 4.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Brush.linearGradient(listOf(look.accentA, look.accentB))))
        Spacer(Modifier.width(10.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.6.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ScreenHeader(title: String, eyebrow: String) {
    val look = LocalLook.current
    Column(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
        Text(
            eyebrow.uppercase(),
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.4.sp, brush = Brush.linearGradient(listOf(look.accentA, look.accentB))),
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardBox(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val look = LocalLook.current
    val shape = RoundedCornerShape(28.dp)
    val border = if (accent) Brush.linearGradient(listOf(look.accentA.copy(alpha = 0.9f), look.accentB.copy(alpha = 0.9f)))
    else Brush.linearGradient(listOf(look.borderHi, look.borderLo))
    val source = remember { MutableInteractionSource() }
    var m = modifier.fillMaxWidth()
    if (onClick != null || onLongClick != null) m = m.pressScale(source, 0.985f)
    m = m.clip(shape)
        .background(Brush.verticalGradient(listOf(look.cardTop, look.cardBottom)))
        .border(if (accent) 1.5.dp else 1.dp, border, shape)
    if (onClick != null || onLongClick != null) {
        m = m.combinedClickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick ?: {}, onLongClick = onLongClick)
    }
    Column(m.padding(18.dp), content = content)
}

@Composable
fun GlowFab(icon: ImageVector, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val look = LocalLook.current
    val source = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.size(66.dp)
            .pressScale(source, 0.92f)
            .shadow(18.dp, CircleShape, ambientColor = look.accentB, spotColor = look.accentA)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(look.btnA, look.btnB)))
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = look.onBtn, modifier = Modifier.size(32.dp)) }
}

/**
 * Full-screen editor with a fixed footer. The footer and the focused text box always stay above the keyboard.
 */
@Composable
fun EditorDialog(
    title: String,
    onDismiss: () -> Unit,
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val look = LocalLook.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(look.bgTop, look.bgBottom)))) {
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Close, "Close") }
                    }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        content = content,
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = footer)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Confetti
// ---------------------------------------------------------------------------------------------

private class Piece(
    val x0: Float, val vx: Float, val vy: Float, val delay: Float, val wobble: Float, val wobbleSpeed: Float,
    val phase: Float, val spin: Float, val w: Float, val h: Float, val round: Boolean, val color: Color,
)

/** Rains confetti over the whole area each time [trigger] changes to a new non-zero number. Does not block touches. */
@Composable
fun ConfettiOverlay(trigger: Int, modifier: Modifier = Modifier) {
    if (trigger == 0) return
    val palette = remember {
        listOf(Color(0xFFF6D88F), Color(0xFFE5A93D), Color(0xFFA48CFF), Color(0xFF4FD6A5), Color(0xFFFF7088), Color(0xFF6CC8FF), Color(0xFFFFFFFF))
    }
    val pieces = remember(trigger) {
        val r = Random(trigger * 7919 + System.nanoTime().toInt())
        List(150) {
            Piece(
                x0 = r.nextFloat(), vx = (r.nextFloat() - 0.5f) * 0.12f, vy = 0.04f + r.nextFloat() * 0.2f,
                delay = r.nextFloat() * 0.9f, wobble = 0.4f + r.nextFloat(), wobbleSpeed = 2f + r.nextFloat() * 4f,
                phase = r.nextFloat() * 6.28f, spin = (r.nextFloat() - 0.5f) * 720f,
                w = 7f + r.nextFloat() * 8f, h = 4f + r.nextFloat() * 5f, round = r.nextInt(4) == 0,
                color = palette[r.nextInt(palette.size)],
            )
        }
    }
    val seconds = 3.8f
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { progress.animateTo(1f, tween((seconds * 1000).toInt(), easing = LinearEasing)) }
    Canvas(modifier.fillMaxSize()) {
        val t = progress.value
        if (t >= 1f) return@Canvas
        val time = t * seconds
        val fade = 1f - ((t - 0.82f) / 0.18f).coerceIn(0f, 1f)
        val dp = density
        for (p in pieces) {
            val lt = time - p.delay
            if (lt <= 0f) continue
            val x = (p.x0 + p.vx * lt) * size.width + sin(lt * p.wobbleSpeed + p.phase) * 22f * dp * p.wobble
            val y = (-0.05f + p.vy * lt + 0.5f * 0.11f * lt * lt) * size.height
            if (y > size.height + 20f) continue
            val w = p.w * dp
            val h = p.h * dp
            if (p.round) {
                drawCircle(p.color.copy(alpha = fade), radius = w / 2, center = Offset(x, y))
            } else {
                rotate(p.spin * lt, pivot = Offset(x, y)) {
                    drawRect(p.color.copy(alpha = fade), topLeft = Offset(x - w / 2, y - h / 2), size = Size(w, h))
                }
            }
        }
    }
}
