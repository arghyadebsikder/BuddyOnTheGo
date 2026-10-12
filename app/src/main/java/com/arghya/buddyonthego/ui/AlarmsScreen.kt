package com.arghya.buddyonthego.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.alarm.AlarmScheduler
import com.arghya.buddyonthego.data.Alarm
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.domain.NextTrigger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlarmsScreen(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val dao = ServiceLocator.db.alarmDao()
    val alarms by dao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val h12 = rememberBool(K.H12, true)
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(20_000); value = System.currentTimeMillis() } }

    val nextAlarm = alarms.filter { it.enabled }.mapNotNull { a -> AlarmScheduler.nextTriggerFor(a, now)?.let { a to it } }.minByOrNull { it.second }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Alarms", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                if (nextAlarm != null) {
                    Text("Rings in ${Fmt.duration(nextAlarm.second - now)}", color = MaterialTheme.colorScheme.secondary)
                } else {
                    Text("No alarm is set", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (alarms.isEmpty()) item {
                CardBox { Text("Tap + to add your first alarm. Setting a common alarm takes 3 taps.") }
            }
            items(alarms, key = { it.id }) { a ->
                CardBox(Modifier.combinedClickable(onClick = { editing = a }, onLongClick = { menuFor = a.id })) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Fmt.time(a.hour, a.minute, h12), style = ClockStyle)
                            if (a.label.isNotBlank()) Text(a.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (a.daysMask == 0) "Once" else if (a.daysMask == 127) "Every day"
                                else Fmt.dayLetters.indices.filter { a.daysMask and (1 shl it) != 0 }
                                    .joinToString(" ") { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it] },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (a.skipNextDate != null) Text("Skipping ${a.skipNextDate}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                        Switch(checked = a.enabled, onCheckedChange = { on ->
                            scope.launch {
                                val updated = a.copy(enabled = on)
                                dao.upsert(updated)
                                AlarmScheduler.schedule(ctx, updated)
                            }
                        })
                    }
                    DropdownMenu(expanded = menuFor == a.id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                            menuFor = null
                            scope.launch {
                                val id = dao.upsert(a.copy(id = 0))
                                AlarmScheduler.schedule(ctx, a.copy(id = id))
                            }
                        })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = {
                            menuFor = null
                            scope.launch { deleteWithUndo(ctx, a, snackbar) }
                        })
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { editing = Alarm(id = 0, hour = 7, minute = 0) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Default.Add, "Add alarm") }
    }

    editing?.let { a ->
        AlarmEditor(
            initial = a, h12 = h12, onDismiss = { editing = null },
            onSave = { saved ->
                editing = null
                scope.launch {
                    val id = dao.upsert(saved)
                    AlarmScheduler.schedule(ctx, saved.copy(id = if (saved.id == 0L) id else saved.id))
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AlarmEditor(initial: Alarm, h12: Boolean, onDismiss: () -> Unit, onSave: (Alarm) -> Unit, onDelete: (() -> Unit)?) {
    val timeState = rememberTimePickerState(initial.hour, initial.minute, is24Hour = !h12)
    var mask by remember { mutableIntStateOf(initial.daysMask) }
    var label by remember { mutableStateOf(initial.label) }
    var snooze by remember { mutableIntStateOf(initial.snoozeMinutes) }
    var maxSnooze by remember { mutableIntStateOf(initial.maxSnoozes) }
    var ramp by remember { mutableIntStateOf(initial.volumeRampSeconds) }
    var challenge by remember { mutableIntStateOf(initial.challengeType) }
    var vibe by remember { mutableIntStateOf(initial.vibrationPattern) }
    var skip by remember { mutableStateOf(initial.skipNextDate) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (initial.id == 0L) "New alarm" else "Edit alarm", style = MaterialTheme.typography.headlineSmall)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = timeState) }

                Text("Repeat", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Fmt.dayLetters.forEachIndexed { i, d ->
                        FilterChip(
                            selected = mask and (1 shl i) != 0,
                            onClick = { mask = mask xor (1 shl i) },
                            label = { Text(d) },
                        )
                    }
                }
                Text(if (mask == 0) "Rings once" else "Repeats on the days above", style = MaterialTheme.typography.bodySmall)

                OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Stepper("Snooze length", snooze, 1, 30, 1, " min") { snooze = it }
                Stepper("Maximum snoozes", maxSnooze, 0, 10) { maxSnooze = it }
                Stepper("Gradual volume", ramp, 0, 120, 5, " s") { ramp = it }

                Text("Dismiss challenge", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Hold", "Math", "Shake").forEachIndexed { i, n ->
                        FilterChip(selected = challenge == i, onClick = { challenge = i }, label = { Text(n) })
                    }
                }
                Text("Vibration", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Standard", "Strong").forEachIndexed { i, n ->
                        FilterChip(selected = vibe == i, onClick = { vibe = i }, label = { Text(n) })
                    }
                }

                if (mask != 0) {
                    SwitchRow(
                        "Skip next ring", skip?.let { "Skipping $it" } ?: "Skip one date, for example a holiday",
                        checked = skip != null,
                    ) { on ->
                        skip = if (on) {
                            AlarmScheduler.nextTriggerFor(
                                Alarm(hour = timeState.hour, minute = timeState.minute, daysMask = mask), ignoreSkip = true,
                            )?.let { NextTrigger.dateOf(it, ZoneId.systemDefault()).toString() }
                        } else null
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = {
                        onSave(initial.copy(
                            hour = timeState.hour, minute = timeState.minute, daysMask = mask, label = label.trim(),
                            enabled = true, snoozeMinutes = snooze, maxSnoozes = maxSnooze, volumeRampSeconds = ramp,
                            challengeType = challenge, vibrationPattern = vibe, skipNextDate = if (mask == 0) null else skip,
                        ))
                    }, modifier = Modifier.weight(1f)) { Text("Save") }
                }
                if (onDelete != null) TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete alarm") }
            }
        }
    }
}
