package com.arghya.buddyonthego.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.alarm.AlarmScheduler
import com.arghya.buddyonthego.alarm.Challenge
import com.arghya.buddyonthego.data.Alarm
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.domain.MathChallenge
import com.arghya.buddyonthego.domain.NextTrigger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

@Composable
fun AlarmsScreen(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val dao = ServiceLocator.db.alarmDao()
    val alarms by dao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val h12 = rememberBool(K.H12, true)
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    val pad = listPadding()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "hero") {
                ScreenHeader("Alarms", Fmt.greeting())
                Spacer(Modifier.height(10.dp))
                NextAlarmHero(alarms)
            }
            if (alarms.isEmpty()) item(key = "empty") {
                CardBox { Text("Tap the + button to add your first alarm. It takes 3 taps.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(alarms, key = { it.id }) { a ->
                CardBox(onClick = { editing = a }, onLongClick = { menuFor = a.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            ClockText(
                                a.hour, a.minute, h12, 48.sp,
                                color = if (a.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (a.label.isNotBlank()) Text(a.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Spacer(Modifier.height(6.dp))
                            if (a.daysMask == 0) Text("Once", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Fmt.dayLetters.forEachIndexed { i, d ->
                                    val on = a.daysMask and (1 shl i) != 0
                                    Text(
                                        d, style = MaterialTheme.typography.bodyLarge,
                                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                            if (a.skipNextDate != null) Text("Skipping ${a.skipNextDate}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        Switch(checked = a.enabled, onCheckedChange = { on ->
                            scope.launch {
                                val updated = a.copy(enabled = on)
                                dao.upsert(updated)
                                AlarmScheduler.schedule(ctx, updated)
                            }
                        }, modifier = Modifier.padding(start = 8.dp))
                    }
                    if (menuFor == a.id) DropdownMenu(expanded = true, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("Duplicate", style = MaterialTheme.typography.bodyLarge) }, onClick = {
                            menuFor = null
                            scope.launch {
                                val id = dao.upsert(a.copy(id = 0))
                                AlarmScheduler.schedule(ctx, a.copy(id = id))
                            }
                        })
                        DropdownMenuItem(text = { Text("Delete", style = MaterialTheme.typography.bodyLarge) }, onClick = {
                            menuFor = null
                            scope.launch { deleteWithUndo(ctx, a, snackbar) }
                        })
                    }
                }
            }
            if (alarms.isNotEmpty()) item(key = "hint") { DisabledNote("Tap an alarm to change it. Press and hold for more.") }
        }
        GlowFab(
            Icons.Default.Add, "Add alarm",
            Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = pad.calculateBottomPadding()),
        ) { editing = Alarm(id = 0, hour = 7, minute = 0) }
    }

    editing?.let { a ->
        AlarmEditor(
            initial = a, h12 = h12, onDismiss = { editing = null },
            onSave = { saved ->
                editing = null
                scope.launch {
                    val id = dao.upsert(saved)
                    val full = saved.copy(id = if (saved.id == 0L) id else saved.id)
                    AlarmScheduler.schedule(ctx, full)
                    AlarmScheduler.nextTriggerFor(full)?.let {
                        snackbar.showSnackbar("Alarm set. Rings in ${Fmt.duration(it - System.currentTimeMillis())}.")
                    }
                }
            },
            onDelete = if (a.id != 0L) ({ editing = null; scope.launch { deleteWithUndo(ctx, a, snackbar) } }) else null,
        )
    }
}

private suspend fun deleteWithUndo(ctx: android.content.Context, a: Alarm, snackbar: SnackbarHostState) {
    val dao = ServiceLocator.db.alarmDao()
    AlarmScheduler.cancel(ctx, a.id)
    dao.delete(a.id)
    val r = snackbar.showSnackbar("Alarm deleted", actionLabel = "Undo", duration = SnackbarDuration.Short)
    if (r == SnackbarResult.ActionPerformed) {
        val id = dao.upsert(a.copy(id = 0))
        AlarmScheduler.schedule(ctx, a.copy(id = id))
    }
}

@Composable
private fun AlarmEditor(initial: Alarm, h12: Boolean, onDismiss: () -> Unit, onSave: (Alarm) -> Unit, onDelete: (() -> Unit)?) {
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    var mask by remember { mutableIntStateOf(initial.daysMask) }
    var label by remember { mutableStateOf(initial.label) }
    var snooze by remember { mutableIntStateOf(initial.snoozeMinutes) }
    var maxSnooze by remember { mutableIntStateOf(initial.maxSnoozes) }
    var ramp by remember { mutableIntStateOf(initial.volumeRampSeconds) }
    var challenge by remember { mutableIntStateOf(initial.challengeType) }
    var mathLevel by remember { mutableIntStateOf(initial.mathDifficulty) }
    var vibe by remember { mutableIntStateOf(initial.vibrationPattern) }
    var skip by remember { mutableStateOf(initial.skipNextDate) }
    val example = remember(mathLevel) { MathChallenge.generate(mathLevel) }

    // Live "rings in ..." so the person can see what the alarm will do before saving.
    val preview = remember(hour, minute, mask) {
        AlarmScheduler.nextTriggerFor(Alarm(hour = hour, minute = minute, daysMask = mask), ignoreSkip = true)
    }

    EditorDialog(
        title = if (initial.id == 0L) "New alarm" else "Edit alarm",
        onDismiss = onDismiss,
        footer = {
            BigButton("Cancel", onDismiss, Modifier.weight(1f), BtnStyle.Outline, height = 60.dp)
            BigButton("Save", {
                onSave(initial.copy(
                    hour = hour, minute = minute, daysMask = mask, label = label.trim(),
                    enabled = true, snoozeMinutes = snooze, maxSnoozes = maxSnooze, volumeRampSeconds = ramp,
                    challengeType = challenge, mathDifficulty = mathLevel, vibrationPattern = vibe,
                    skipNextDate = if (mask == 0) null else skip,
                ))
            }, Modifier.weight(1f), height = 60.dp)
        },
    ) {
        CardBox(accent = true) {
            Text("Swipe up or down to set the time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(6.dp))
            TimeWheel(initial.hour, initial.minute, is24 = !h12, onChange = { h, m -> hour = h; minute = m })
            if (preview != null) {
                Text(
                    "Rings in ${Fmt.duration(preview - System.currentTimeMillis())}",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp), color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
                )
            }
        }

        Text("Repeat", style = MaterialTheme.typography.titleSmall)
        ChipFlow {
            BuddyChip(mask == 0, "Once", { mask = 0 })
            BuddyChip(mask == 127, "Every day", { mask = 127 })
            BuddyChip(mask == 31, "Weekdays", { mask = 31 })
            BuddyChip(mask == 96, "Weekend", { mask = 96 })
        }
        DayPicker(mask) { mask = it }
        Text(if (mask == 0) "Rings once, then turns itself off." else "Repeats on the highlighted days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        BuddyTextField(label, { label = it }, "Label (optional)")

        CardBox {
            Stepper("Snooze length", snooze, 1, 30, 1, " min") { snooze = it }
            Stepper("Maximum snoozes", maxSnooze, 0, 10) { maxSnooze = it }
            Stepper("Gradual volume", ramp, 0, 120, 5, " s") { ramp = it }
        }

        Text("To switch it off, I will", style = MaterialTheme.typography.titleSmall)
        ChipFlow {
            Challenge.names.forEachIndexed { i, n -> BuddyChip(challenge == i, n, { challenge = i }) }
        }
        Text(Challenge.hints[challenge.coerceIn(0, Challenge.hints.lastIndex)], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (challenge == Challenge.MATH) {
            Text("Math difficulty", style = MaterialTheme.typography.titleSmall)
            ChipFlow {
                MathChallenge.levelNames.forEachIndexed { i, n -> BuddyChip(mathLevel == i, n, { mathLevel = i }) }
            }
            Text(
                "${MathChallenge.levelHints[mathLevel.coerceIn(0, 2)]}. Example: ${example.text} = ?",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text("Vibration", style = MaterialTheme.typography.titleSmall)
        ChipFlow {
            listOf("Standard", "Strong").forEachIndexed { i, n -> BuddyChip(vibe == i, n, { vibe = i }) }
        }

        if (mask != 0) {
            CardBox {
                SwitchRow(
                    "Skip next ring", skip?.let { "Skipping $it" } ?: "Skip one date, for example a holiday",
                    checked = skip != null,
                ) { on ->
                    skip = if (on) {
                        AlarmScheduler.nextTriggerFor(
                            Alarm(hour = hour, minute = minute, daysMask = mask), ignoreSkip = true,
                        )?.let { NextTrigger.dateOf(it, ZoneId.systemDefault()).toString() }
                    } else null
                }
            }
        }
        if (onDelete != null) BigButton("Delete alarm", onDelete, Modifier.fillMaxWidth(), BtnStyle.Danger)
        Spacer(Modifier.height(8.dp))
    }
}

/** Own clock so the 20-second tick never recomposes the whole list while scrolling. */
@Composable
private fun NextAlarmHero(alarms: List<Alarm>) {
    val h12 = rememberBool(K.H12, true)
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(20_000); value = System.currentTimeMillis() } }
    val next = remember(alarms, now) {
        alarms.filter { it.enabled }.mapNotNull { a -> AlarmScheduler.nextTriggerFor(a, now)?.let { a to it } }.minByOrNull { it.second }
    }
    CardBox(accent = next != null) {
        Text("NEXT ALARM", style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (next != null) {
            GradientText("in " + Fmt.duration(next.second - now), 44)
            val (m, ap) = Fmt.parts(next.first.hour, next.first.minute, h12)
            Text("Rings at $m $ap".trim() + if (next.first.label.isNotBlank()) "  -  ${next.first.label}" else "", style = MaterialTheme.typography.bodyLarge)
        } else {
            Text("No alarm is set", style = MaterialTheme.typography.headlineSmall)
        }
    }
}
