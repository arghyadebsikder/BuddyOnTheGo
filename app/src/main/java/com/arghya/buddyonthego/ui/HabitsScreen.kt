package com.arghya.buddyonthego.ui

import android.content.Intent
import android.provider.Settings as AS
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.data.Habit
import com.arghya.buddyonthego.data.HabitLog
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.domain.HabitWindow
import com.arghya.buddyonthego.domain.StreakResult
import com.arghya.buddyonthego.domain.Streaks
import com.arghya.buddyonthego.reminder.HabitRunner
import com.arghya.buddyonthego.reminder.ReminderScheduler
import com.arghya.buddyonthego.reminder.SoundGuard
import com.arghya.buddyonthego.reminder.SoundMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle as DayStyle
import java.util.Locale

private class HabitStat(val done: Set<LocalDate>, val streak: StreakResult)

private val habitColors = listOf(
    Color(0xFFE5A93D), Color(0xFF8F7BFF), Color(0xFF2FBF8F), Color(0xFFEF6B7D), Color(0xFF3FA7E8), Color(0xFFD27BE0),
)

@Composable
fun HabitsScreen(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val dao = ServiceLocator.db.habitDao()
    val habits by dao.observeHabits().collectAsState(initial = emptyList())
    val logs by dao.observeLogs().collectAsState(initial = emptyList())
    val grace = rememberBool(K.STREAK_GRACE, false)
    val h12 = rememberBool(K.H12, true)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val today = LocalDate.now()
    // Checks start and finish times a few times a minute, so cards switch to "Running" on time.
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(15_000); value = System.currentTimeMillis() } }
    val stats = remember(habits, logs, grace, today) {
        val byHabit = logs.groupBy { it.habitId }
        habits.associate { h ->
            val mine = byHabit[h.id].orEmpty()
            val done = mine.filter { it.status == 0 }.map { LocalDate.parse(it.date) }.toSet()
            val skipped = mine.filter { it.status == 1 }.map { LocalDate.parse(it.date) }.toSet()
            h.id to HabitStat(done, Streaks.compute(h.scheduleType, h.daysMask, h.targetPerWeek, done, skipped, today, grace))
        }
    }
    var editing by remember { mutableStateOf<Habit?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var askPolicy by remember { mutableStateOf(false) }
    val pad = listPadding()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "header") { ScreenHeader("Habits", "Build streaks") }
            if (habits.isEmpty()) item { CardBox { Text("Tap the + button to start a habit. Give it a start and finish time and Buddy will track it for you.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(habits, key = { it.id }) { h ->
                val st = stats[h.id] ?: HabitStat(emptySet(), StreakResult(0, 0))
                val done = st.done
                val streak = st.streak
                val color = habitColors[h.colorIndex % habitColors.size]
                val doneToday = today in done
                CardBox(onClick = { expanded = if (expanded == h.id) null else h.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(60.dp).clip(CircleShape)
                                .background(if (doneToday) color else color.copy(alpha = 0.2f))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    scope.launch {
                                        if (doneToday) dao.deleteLog(h.id, today.toString())
                                        else {
                                            val old = dao.logFor(h.id, today.toString())
                                            dao.upsertLog(HabitLog(id = old?.id ?: 0, habitId = h.id, date = today.toString(), status = 0))
                                            if (ServiceLocator.settings.modHabit) HabitRunner.finishEarly(ctx, h)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (doneToday) Icon(Icons.Default.Check, "Done today, tap to undo", tint = Color.White, modifier = Modifier.size(32.dp))
                            else Text(h.name.trim().take(1).uppercase(), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                            Text(
                                "Streak ${streak.current}${if (h.scheduleType == 2) " wk" else " d"}  -  best ${streak.best}",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        BigButton("Edit", { editing = h }, style = BtnStyle.Text, height = 48.dp)
                    }
                    WeekStrip(today, done, color)
                    TimelineBlock(h, now, doneToday, h12, color, onSoundChange = { mode ->
                        scope.launch {
                            val upd = h.copy(soundMode = mode)
                            dao.upsertHabit(upd)
                            if (ServiceLocator.settings.modHabit) HabitRunner.applySoundMode(ctx, upd)
                        }
                        if (mode == SoundMode.SILENT && !SoundGuard.hasPolicyAccess(ctx)) askPolicy = true
                    })
                    if (expanded == h.id) {
                        Spacer(Modifier.height(12.dp))
                        Heatmap(today, done, color)
                        val last30 = (0 until 30).count { today.minusDays(it.toLong()) in done }
                        Text("Last 30 days: ${last30 * 100 / 30}% done", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("Tap the card to see the last 12 weeks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        GlowFab(Icons.Default.Add, "Add habit", Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = pad.calculateBottomPadding())) {
            editing = Habit(id = 0)
        }
    }

    editing?.let { h ->
        HabitEditor(
            h = h, h12 = h12,
            onDismiss = { editing = null },
            onSave = { saved ->
                editing = null
                scope.launch {
                    val id = dao.upsertHabit(saved)
                    val withId = saved.copy(id = if (saved.id == 0L) id else saved.id)
                    if (ServiceLocator.settings.modHabit) ReminderScheduler.scheduleHabit(ctx, withId)
                    snackbar.showSnackbar(if (withId.reminderMinutes >= 0) "Saved. Buddy will notify you at the start time." else "Habit saved.")
                }
            },
            onDelete = if (h.id != 0L) ({
                editing = null
                scope.launch { HabitRunner.stop(ctx, h.id); dao.deleteLogsFor(h.id); dao.deleteHabit(h.id) }
            }) else null,
        )
    }

    if (askPolicy) PolicyAccessDialog(onDismiss = { askPolicy = false })
}

/** Silent needs the "Do Not Disturb access" switch in Android settings, which only the person can turn on. */
@Composable
fun PolicyAccessDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow Do Not Disturb access") },
        text = { Text("To mute every sound, Android needs your permission once. Without it, Silent only vibrates. Your Buddy alarms always still ring.", style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            BigButton("Open settings", {
                onDismiss()
                try { ctx.startActivity(Intent(AS.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            }, height = 48.dp)
        },
        dismissButton = { BigButton("Not now", onDismiss, style = BtnStyle.Text, height = 48.dp) },
    )
}

// ---------------------------------------------------------------------------------------------
// Timeline
// ---------------------------------------------------------------------------------------------

@Composable
private fun TimelineBlock(h: Habit, now: Long, doneToday: Boolean, h12: Boolean, color: Color, onSoundChange: (Int) -> Unit) {
    if (h.reminderMinutes < 0) return
    val look = LocalLook.current
    val start = h.reminderMinutes
    Spacer(Modifier.height(10.dp))
    if (h.endMinutes < 0) {
        Text("Reminder at ${Fmt.minutes(start, h12)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val zone = ZoneId.systemDefault()
    val window = HabitRunner.windowNow(h, now)
    val running = window != null && !doneToday
    val span = "${Fmt.minutes(start, h12)} - ${Fmt.minutes(h.endMinutes, h12)}"

    if (running && window != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(look.success))
            Spacer(Modifier.width(8.dp))
            Text("Running now", style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp), color = look.success)
        }
        Spacer(Modifier.height(8.dp))
        RunningTrack(window, h12, color)
        Spacer(Modifier.height(12.dp))
        Text("Sound while running", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        ChipFlow {
            SoundMode.names.forEachIndexed { i, n -> BuddyChip(h.soundMode == i, n, { onSoundChange(i) }) }
        }
        Text(SoundMode.hints[h.soundMode.coerceIn(0, 2)], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        return
    }

    // Not running: say where it is in the day.
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val scheduledToday = ReminderScheduler.maskOf(h) and (1 shl (today.dayOfWeek.value - 1)) != 0
    val startToday = ZonedDateTime.of(today, LocalTime.of(start / 60, start % 60), zone).toInstant().toEpochMilli()
    val status = when {
        doneToday && window != null -> "Done early today"
        !scheduledToday -> {
            val next = HabitWindow.nextStart(start, ReminderScheduler.maskOf(h), now, zone)
            if (next != null) {
                val z = java.time.Instant.ofEpochMilli(next).atZone(zone)
                "Next: ${z.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.getDefault())} ${Fmt.time(z.hour, z.minute, h12)}"
            } else "Not scheduled"
        }
        now < startToday -> "Starts at ${Fmt.minutes(start, h12)}"
        else -> "Finished for today"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(span, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("Sound while running: ${SoundMode.names[h.soundMode.coerceIn(0, 2)]}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A line that fills from the start time to the finish time, with a glowing dot that moves along it every second. */
@Composable
private fun RunningTrack(window: HabitWindow.Window, h12: Boolean, color: Color) {
    val look = LocalLook.current
    val now by produceState(System.currentTimeMillis(), window.startMs) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    val progress by animateFloatAsState(window.progress(now), tween(1000, easing = LinearEasing), label = "progress")
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "dot")
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val zone = ZoneId.systemDefault()
    val s = java.time.Instant.ofEpochMilli(window.startMs).atZone(zone)
    val e = java.time.Instant.ofEpochMilli(window.endMs).atZone(zone)
    val left = window.endMs - now

    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val cy = size.height / 2
        val barH = 10.dp.toPx()
        val r = barH / 2
        drawRoundRect(track, Offset(0f, cy - r), Size(size.width, barH), CornerRadius(r))
        val x = (size.width * progress).coerceIn(0f, size.width)
        if (x > 0f) {
            drawRoundRect(
                Brush.horizontalGradient(listOf(look.accentA, color), startX = 0f, endX = size.width),
                Offset(0f, cy - r), Size(x.coerceAtLeast(barH), barH), CornerRadius(r),
            )
        }
        val dotX = x.coerceIn(r, size.width - r)
        drawCircle(color.copy(alpha = 0.35f * (1f - pulse)), radius = 10.dp.toPx() + 8.dp.toPx() * pulse, center = Offset(dotX, cy))
        drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(dotX, cy))
        drawCircle(color, radius = 6.dp.toPx(), center = Offset(dotX, cy))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(Fmt.time(s.hour, s.minute, h12), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (left < 60_000) "Less than 1 min left" else "${Fmt.duration(left)} left", style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), color = MaterialTheme.colorScheme.onSurface)
        Text(Fmt.time(e.hour, e.minute, h12), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------------------------

@Composable
private fun HabitEditor(h: Habit, h12: Boolean, onDismiss: () -> Unit, onSave: (Habit) -> Unit, onDelete: (() -> Unit)?) {
    val ctx = LocalContext.current
    var name by remember(h.id) { mutableStateOf(h.name) }
    var colorIdx by remember(h.id) { mutableIntStateOf(h.colorIndex) }
    var type by remember(h.id) { mutableIntStateOf(h.scheduleType) }
    var mask by remember(h.id) { mutableIntStateOf(if (h.daysMask == 0) 127 else h.daysMask) }
    var target by remember(h.id) { mutableIntStateOf(h.targetPerWeek) }
    var start by remember(h.id) { mutableIntStateOf(h.reminderMinutes) }
    var end by remember(h.id) { mutableIntStateOf(h.endMinutes) }
    var sound by remember(h.id) { mutableIntStateOf(h.soundMode) }
    var startDialog by remember { mutableStateOf(false) }
    var endDialog by remember { mutableStateOf(false) }
    var askPolicy by remember { mutableStateOf(false) }

    val sameTime = start >= 0 && end >= 0 && start == end
    val canSave = name.isNotBlank() && !sameTime

    EditorDialog(
        title = if (h.id == 0L) "New habit" else "Edit habit",
        onDismiss = onDismiss,
        footer = {
            BigButton("Cancel", onDismiss, Modifier.weight(1f), BtnStyle.Outline, height = 60.dp)
            BigButton("Save", {
                onSave(h.copy(
                    name = name.trim(), colorIndex = colorIdx, scheduleType = type,
                    daysMask = if (type == 1) mask else 127, targetPerWeek = target,
                    reminderMinutes = start, endMinutes = if (start >= 0) end else -1, soundMode = if (start >= 0 && end >= 0) sound else SoundMode.GENERAL,
                ))
            }, Modifier.weight(1f), enabled = canSave, height = 60.dp)
        },
    ) {
        BuddyTextField(name, { name = it }, "Name (for example Study)")

        Text("Colour", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            habitColors.forEachIndexed { i, c ->
                Box(Modifier.size(44.dp).clip(CircleShape).background(c).clickable { colorIdx = i }, contentAlignment = Alignment.Center) {
                    if (i == colorIdx) Icon(Icons.Default.Check, "Selected", tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }

        Text("How often", style = MaterialTheme.typography.titleSmall)
        ChipFlow {
            listOf("Every day", "Chosen days", "Times a week").forEachIndexed { i, n -> BuddyChip(type == i, n, { type = i }) }
        }
        if (type == 1) DayPicker(mask) { mask = if (it == 0) mask else it }
        if (type == 2) Stepper("Times per week", target, 1, 7) { target = it }

        CardBox {
            Text("When", style = MaterialTheme.typography.titleSmall)
            TimeRow("Start", start, h12, onSet = { startDialog = true }, onClear = { start = -1; end = -1 })
            if (start >= 0) TimeRow("Finish", end, h12, onSet = { endDialog = true }, onClear = { end = -1 })
            val note = when {
                start < 0 -> "No start time: just tick it off yourself."
                end < 0 -> "Buddy reminds you at the start time. Add a finish time to see a running timeline."
                sameTime -> "Start and finish must be different."
                end < start -> "Runs past midnight. Length ${Fmt.duration(HabitWindow.lengthMinutes(start, end) * 60_000L)}."
                else -> "Runs for ${Fmt.duration(HabitWindow.lengthMinutes(start, end) * 60_000L)}. At the start time Buddy tells you it began and shows a flowing timeline."
            }
            Text(note, style = MaterialTheme.typography.bodySmall, color = if (sameTime) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (start >= 0 && end >= 0) {
            Text("Phone sound while it is running", style = MaterialTheme.typography.titleSmall)
            ChipFlow {
                SoundMode.names.forEachIndexed { i, n ->
                    BuddyChip(sound == i, n, { sound = i; if (i == SoundMode.SILENT && !SoundGuard.hasPolicyAccess(ctx)) askPolicy = true })
                }
            }
            Text(SoundMode.hints[sound.coerceIn(0, 2)], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("The sound goes back to normal when the task ends. You can also change it while the task is running.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (onDelete != null) BigButton("Delete habit", onDelete, Modifier.fillMaxWidth(), BtnStyle.Danger)
        Spacer(Modifier.height(8.dp))
    }

    if (startDialog) TimeDialog(if (start < 0) 8 else start / 60, if (start < 0) 0 else start % 60, !h12, { startDialog = false }, { hh, mm ->
        start = hh * 60 + mm
        startDialog = false
    }, "Start time")
    if (endDialog) TimeDialog(if (end < 0) ((start / 60 + 1) % 24) else end / 60, if (end < 0) start % 60 else end % 60, !h12, { endDialog = false }, { hh, mm ->
        end = hh * 60 + mm
        endDialog = false
    }, "Finish time")
    if (askPolicy) PolicyAccessDialog(onDismiss = { askPolicy = false })
}

@Composable
private fun TimeRow(label: String, minutes: Int, h12: Boolean, onSet: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (minutes < 0) "Not set" else Fmt.minutes(minutes, h12),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 24.sp),
                color = if (minutes < 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        BigButton(if (minutes < 0) "Set" else "Change", onSet, style = BtnStyle.Tonal, height = 48.dp)
        if (minutes >= 0) {
            Spacer(Modifier.width(6.dp))
            BigButton("Clear", onClear, style = BtnStyle.Text, height = 48.dp)
        }
    }
}

@Composable
private fun WeekStrip(today: LocalDate, done: Set<LocalDate>, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
        for (i in 6 downTo 0) {
            val d = today.minusDays(i.toLong())
            Box(Modifier.size(20.dp).clip(CircleShape).background(if (d in done) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
        }
    }
}

/** HAB-4: calendar heatmap drawn with Compose Canvas (12 weeks). */
@Composable
private fun Heatmap(today: LocalDate, done: Set<LocalDate>, color: Color) {
    val weeks = 12
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val cell = (size.width / weeks).coerceAtMost(size.height / 7)
        val gap = cell * 0.14f
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val start = monday.minusWeeks((weeks - 1).toLong())
        for (w in 0 until weeks) {
            for (d in 0 until 7) {
                val date = start.plusDays((w * 7 + d).toLong())
                if (date.isAfter(today)) continue
                drawRoundRect(
                    color = if (date in done) color else empty,
                    topLeft = Offset(w * cell, d * cell),
                    size = Size(cell - gap, cell - gap),
                    cornerRadius = CornerRadius(cell * 0.2f),
                )
            }
        }
    }
}
