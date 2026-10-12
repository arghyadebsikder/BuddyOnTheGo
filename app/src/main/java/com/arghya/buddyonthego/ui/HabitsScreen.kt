package com.arghya.buddyonthego.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.data.Habit
import com.arghya.buddyonthego.data.HabitLog
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.domain.Streaks
import com.arghya.buddyonthego.reminder.ReminderScheduler
import kotlinx.coroutines.launch
import java.time.LocalDate

private val habitColors = listOf(
    Color(0xFF5C60D6), Color(0xFFFFB300), Color(0xFF2E9E6B), Color(0xFFE5575B), Color(0xFF2A8FD1), Color(0xFF9B59B6),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitsScreen() {
    val ctx = LocalContext.current
    val dao = ServiceLocator.db.habitDao()
    val habits by dao.observeHabits().collectAsState(initial = emptyList())
    val logs by dao.observeLogs().collectAsState(initial = emptyList())
    val grace = rememberBool(K.STREAK_GRACE, false)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val today = LocalDate.now()
    var editing by remember { mutableStateOf<Habit?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Habits", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
            if (habits.isEmpty()) item { CardBox { Text("Tap + to start a habit. One tap a day builds a streak.") } }
            items(habits, key = { it.id }) { h ->
                val mine = logs.filter { it.habitId == h.id }
                val done = mine.filter { it.status == 0 }.map { LocalDate.parse(it.date) }.toSet()
                val skipped = mine.filter { it.status == 1 }.map { LocalDate.parse(it.date) }.toSet()
                val streak = Streaks.compute(h.scheduleType, h.daysMask, h.targetPerWeek, done, skipped, today, grace)
                val color = habitColors[h.colorIndex % habitColors.size]
                CardBox(Modifier.clickable { expanded = if (expanded == h.id) null else h.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(52.dp).clip(CircleShape)
                                .background(if (today in done) color else color.copy(alpha = 0.18f))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    scope.launch {
                                        if (today in done) dao.deleteLog(h.id, today.toString())
                                        else {
                                            val old = dao.logFor(h.id, today.toString())
                                            dao.upsertLog(HabitLog(id = old?.id ?: 0, habitId = h.id, date = today.toString(), status = 0))
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (today in done) Icon(Icons.Default.Check, "Done today", tint = Color.White) else Text(h.icon)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Streak ${streak.current}${if (h.scheduleType == 2) " wk" else " d"}  -  best ${streak.best}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                            )
                            WeekStrip(today, done, color)
                        }
                        TextButton(onClick = { editing = h }) { Text("Edit") }
                    }
                    if (expanded == h.id) {
                        Spacer(Modifier.height(10.dp))
                        Heatmap(today, done, color)
                        val last30 = (0 until 30).count { today.minusDays(it.toLong()) in done }
                        Text("Last 30 days: ${last30 * 100 / 30}% done", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        FloatingActionButton(onClick = { editing = Habit(id = 0) }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            Icon(Icons.Default.Add, "Add habit")
        }
    }

    editing?.let { h ->
        var name by remember(h.id) { mutableStateOf(h.name) }
        var icon by remember(h.id) { mutableStateOf(h.icon) }
        var colorIdx by remember(h.id) { mutableIntStateOf(h.colorIndex) }
        var type by remember(h.id) { mutableIntStateOf(h.scheduleType) }
        var mask by remember(h.id) { mutableIntStateOf(if (h.daysMask == 0) 127 else h.daysMask) }
        var target by remember(h.id) { mutableIntStateOf(h.targetPerWeek) }
        var remind by remember(h.id) { mutableIntStateOf(h.reminderMinutes) }
        var timeDialog by remember { mutableStateOf(false) }
        val h12 = rememberBool(K.H12, true)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (h.id == 0L) "New habit" else "Edit habit") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(icon, { icon = it.take(2) }, label = { Text("Icon (an emoji)") }, singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        habitColors.forEachIndexed { i, c ->
                            Box(Modifier.size(34.dp).clip(CircleShape).background(c).clickable { colorIdx = i }, contentAlignment = Alignment.Center) {
                                if (i == colorIdx) Icon(Icons.Default.Check, "Selected", tint = Color.White)
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Every day", "Chosen days", "Times a week").forEachIndexed { i, n -> FilterChip(selected = type == i, onClick = { type = i }, label = { Text(n) }) }
                    }
                    if (type == 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Fmt.dayLetters.forEachIndexed { i, d -> FilterChip(selected = mask and (1 shl i) != 0, onClick = { mask = mask xor (1 shl i) }, label = { Text(d) }) }
                    }
                    if (type == 2) Stepper("Per week", target, 1, 7) { target = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (remind < 0) "No reminder" else "Reminder ${Fmt.time(remind / 60, remind % 60, h12)}", Modifier.weight(1f))
                        TextButton(onClick = { timeDialog = true }) { Text(if (remind < 0) "Set" else "Change") }
                        if (remind >= 0) TextButton(onClick = { remind = -1 }) { Text("Clear") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    editing = null
                    scope.launch {
                        val saved = h.copy(name = name.trim(), icon = icon.ifBlank { "✓" }, colorIndex = colorIdx, scheduleType = type, daysMask = if (type == 1) mask else 127, targetPerWeek = target, reminderMinutes = remind)
                        val id = dao.upsertHabit(saved)
                        val withId = saved.copy(id = if (saved.id == 0L) id else saved.id)
                        if (ServiceLocator.settings.modHabit) ReminderScheduler.scheduleHabit(ctx, withId)
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (h.id != 0L) TextButton(onClick = {
                        editing = null
                        scope.launch { ReminderScheduler.cancelHabit(ctx, h.id); dao.deleteLogsFor(h.id); dao.deleteHabit(h.id) }
                    }) { Text("Delete") }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
        if (timeDialog) TimeDialog(if (remind < 0) 8 else remind / 60, if (remind < 0) 0 else remind % 60, !h12, { timeDialog = false }) { hh, mm -> remind = hh * 60 + mm; timeDialog = false }
    }
}

@Composable
private fun WeekStrip(today: LocalDate, done: Set<LocalDate>, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
        for (i in 6 downTo 0) {
            val d = today.minusDays(i.toLong())
            Box(Modifier.size(14.dp).clip(CircleShape).background(if (d in done) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
        }
    }
}

/** HAB-4: calendar heatmap drawn with Compose Canvas (12 weeks). */
@Composable
private fun Heatmap(today: LocalDate, done: Set<LocalDate>, color: Color) {
    val weeks = 12
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
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
