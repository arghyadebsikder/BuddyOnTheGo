package com.arghya.buddyonthego.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.data.Task
import com.arghya.buddyonthego.reminder.ReminderScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

/** Everything about the row that is being dragged. Heights are measured per row so swaps are exact. */
private class DragState {
    var id by mutableStateOf<Long?>(null)
    var group by mutableIntStateOf(0)
    var delta by mutableFloatStateOf(0f)
    var pointerStartY = 0f
    var fingerDy = 0f
    val heights = mutableStateMapOf<Long, Int>()
}

/** Moves the dragged row past its neighbour whenever it has been dragged more than half-way over it. */
private fun reorderStep(list: List<Task>, ds: DragState): List<Task> {
    val id = ds.id ?: return list
    var cur = list
    var idx = cur.indexOfFirst { it.id == id }
    if (idx < 0) return list
    var guard = 0
    while (guard++ < 20) {
        if (ds.delta > 0f && idx < cur.lastIndex) {
            val nh = ds.heights[cur[idx + 1].id] ?: break
            if (ds.delta > nh / 2f) {
                cur = cur.toMutableList().also { val t = it.removeAt(idx); it.add(idx + 1, t) }
                ds.delta -= nh
                idx++
                continue
            }
        } else if (ds.delta < 0f && idx > 0) {
            val ph = ds.heights[cur[idx - 1].id] ?: break
            if (ds.delta < -ph / 2f) {
                cur = cur.toMutableList().also { val t = it.removeAt(idx); it.add(idx - 1, t) }
                ds.delta += ph
                idx--
                continue
            }
        }
        break
    }
    return cur
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val dao = ServiceLocator.db.taskDao()
    val all by dao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val h12 = rememberBool(K.H12, true)
    val today = LocalDate.now()
    var quick by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Task?>(null) }
    var celebrate by remember { mutableIntStateOf(0) }
    val pad = listPadding()

    val searching = search.isNotBlank()
    val filtered = remember(all, search) {
        all.filter { search.isBlank() || it.title.contains(search, true) || it.notes.contains(search, true) }
    }
    val starred = remember(filtered) { filtered.filter { !it.done && it.priority == 2 } }
    val normal = remember(filtered) { filtered.filter { !it.done && it.priority != 2 } }
    val finished = remember(filtered) { filtered.filter { it.done } }

    // Local copies so rows can move while a finger is down. They reset whenever the database list changes.
    var starLocal by remember(starred) { mutableStateOf(starred) }
    var normLocal by remember(normal) { mutableStateOf(normal) }
    val ds = remember { DragState() }

    val highCount = all.count { !it.done && it.priority == 2 }
    val upcomingCount = all.count { !it.done && it.priority != 2 }
    val finishedCount = all.count { it.done }

    fun applySwap() {
        if (ds.group == 0) starLocal = reorderStep(starLocal, ds) else normLocal = reorderStep(normLocal, ds)
    }

    fun commitOrder(group: Int) {
        val list = if (group == 0) starLocal else normLocal
        val original = (if (group == 0) starred else normal).map { it.sortOrder }.sorted()
        val slots = if (original.size == list.size && original.toSet().size == original.size) original
        else List(list.size) { (original.firstOrNull() ?: 0L) + it }
        scope.launch { list.forEachIndexed { i, t -> if (t.sortOrder != slots[i]) dao.setSortOrder(t.id, slots[i]) } }
    }

    fun complete(t: Task, checked: Boolean) {
        scope.launch {
            if (checked) {
                val spawnsNext = t.repeatRule != 0 && t.dueAt != null
                val lastOne = !spawnsNext && all.none { !it.done && it.id != t.id }
                dao.upsert(t.copy(done = true, doneAt = System.currentTimeMillis()))
                ReminderScheduler.cancelTask(ctx, t.id)
                if (spawnsNext) { // repeat: create the next occurrence
                    val z = Instant.ofEpochMilli(t.dueAt!!).atZone(ZoneId.systemDefault())
                    val next = when (t.repeatRule) { 1 -> z.plusDays(1); 2 -> z.plusWeeks(1); else -> z.plusMonths(1) }
                    val n = t.copy(id = 0, done = false, doneAt = null, dueAt = next.toInstant().toEpochMilli())
                    val id = dao.upsert(n)
                    if (ServiceLocator.settings.modTodo) ReminderScheduler.scheduleTask(ctx, n.copy(id = id))
                }
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (lastOne) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    celebrate++
                }
            } else {
                val u = t.copy(done = false, doneAt = null)
                dao.upsert(u)
                if (ServiceLocator.settings.modTodo) ReminderScheduler.scheduleTask(ctx, u)
            }
        }
    }

    fun toggleStar(t: Task) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch { dao.setPriority(t.id, if (t.priority == 2) 1 else 2) }
    }

    fun addQuick() {
        val title = quick.trim()
        if (title.isEmpty()) return
        quick = ""
        scope.launch { dao.upsert(Task(title = title, sortOrder = dao.maxSortOrder() + 1)) }
    }

    val scroll = rememberScrollState()
    var viewport by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    // While a row is dragged near the top or bottom edge, the list scrolls by itself.
    LaunchedEffect(ds.id) {
        if (ds.id == null) return@LaunchedEffect
        val edge = with(density) { 90.dp.toPx() }
        while (isActive) {
            delay(16)
            val vp = viewport ?: continue
            val y = ds.pointerStartY + ds.fingerDy
            val speed = when {
                y < vp.top + edge -> -((vp.top + edge - y) / edge).coerceIn(0f, 1f) * 28f
                y > vp.bottom - edge -> ((y - (vp.bottom - edge)) / edge).coerceIn(0f, 1f) * 28f
                else -> 0f
            }
            if (speed != 0f) {
                val moved = scroll.scrollBy(speed)
                if (moved != 0f) { ds.delta += moved; applySwap() }
            }
        }
    }

    @Composable
    fun Group(items: List<Task>, group: Int) {
        items.forEach { t ->
            key(t.id) {
                TaskRow(
                    t = t, h12 = h12, today = today,
                    dragging = ds.id == t.id, offsetY = if (ds.id == t.id) ds.delta else 0f,
                    canDrag = !searching && !t.done && items.size > 1,
                    onHeight = { ds.heights[t.id] = it },
                    onDragStart = { startY ->
                        ds.group = group; ds.id = t.id; ds.delta = 0f; ds.pointerStartY = startY; ds.fingerDy = 0f
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { dy -> ds.delta += dy; ds.fingerDy += dy; applySwap() },
                    onDragEnd = { val g = ds.group; ds.id = null; ds.delta = 0f; commitOrder(g) },
                    onToggle = { complete(t, !t.done) },
                    onStar = { toggleStar(t) },
                    onOpen = { editing = t },
                )
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .onGloballyPositioned { viewport = it.boundsInWindow() }
                    .verticalScroll(scroll, enabled = ds.id == null)
                    .padding(start = 20.dp, end = 20.dp, top = pad.calculateTopPadding(), bottom = 12.dp),
            ) {
                ScreenHeader("Tasks", "Get it done")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatPill(highCount, "High priority", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                    StatPill(upcomingCount, "Upcoming", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    StatPill(finishedCount, "Finished", LocalLook.current.success, Modifier.weight(1f))
                }
                if (all.size >= 6) {
                    Spacer(Modifier.height(12.dp))
                    BuddyTextField(search, { search = it }, "Search tasks")
                }

                if (all.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    CardBox { Text("Type a task below and tap +. Tap the star to mark it high priority. Hold the handle and drag to change the order.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else if (finishedCount == all.size) {
                    Spacer(Modifier.height(12.dp))
                    CardBox(accent = true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.EmojiEvents, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("All done!", style = MaterialTheme.typography.titleMedium)
                                Text("Every task is finished. Nice work.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                if (starLocal.isNotEmpty()) {
                    SectionTitle("High priority")
                    Group(starLocal, 0)
                }
                if (normLocal.isNotEmpty()) {
                    SectionTitle(if (starLocal.isNotEmpty()) "Other tasks" else "Upcoming")
                    Group(normLocal, 1)
                }
                if (finished.isNotEmpty()) {
                    SectionTitle("Finished")
                    finished.forEach { t ->
                        key(t.id) {
                            TaskRow(
                                t = t, h12 = h12, today = today, dragging = false, offsetY = 0f, canDrag = false,
                                onHeight = {}, onDragStart = {}, onDrag = {}, onDragEnd = {},
                                onToggle = { complete(t, false) }, onStar = { toggleStar(t) }, onOpen = { editing = t },
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = pad.calculateBottomPadding()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BuddyTextField(
                    quick, { quick = it }, "New task", Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addQuick() }),
                )
                FilledIconButton(enabled = quick.isNotBlank(), onClick = { addQuick() }, modifier = Modifier.size(60.dp)) {
                    Icon(Icons.Default.Add, "Add task", modifier = Modifier.size(30.dp))
                }
            }
        }
        ConfettiOverlay(celebrate)
    }

    editing?.let { t ->
        TaskEditor(
            t = t, h12 = h12, today = today,
            onDismiss = { editing = null },
            onSave = { saved ->
                editing = null
                scope.launch {
                    val id = dao.upsert(saved)
                    val withId = saved.copy(id = if (saved.id == 0L) id else saved.id)
                    if (ServiceLocator.settings.modTodo) ReminderScheduler.scheduleTask(ctx, withId)
                }
            },
            onDelete = { editing = null; scope.launch { ReminderScheduler.cancelTask(ctx, t.id); dao.delete(t.id) } },
        )
    }
}

@Composable
private fun StatPill(value: Int, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    CardBox(modifier) {
        Text("$value", fontSize = 32.sp, fontWeight = FontWeight.Light, color = color, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), maxLines = 1)
    }
}

@Composable
private fun RoundCheck(checked: Boolean, onToggle: () -> Unit) {
    val look = LocalLook.current
    Box(Modifier.size(52.dp).clickable(onClickLabel = if (checked) "Mark not done" else "Mark done", onClick = onToggle), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .then(if (checked) Modifier.background(look.success) else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(Icons.Default.Check, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun TaskRow(
    t: Task, h12: Boolean, today: LocalDate, dragging: Boolean, offsetY: Float, canDrag: Boolean,
    onHeight: (Int) -> Unit, onDragStart: (Float) -> Unit, onDrag: (Float) -> Unit, onDragEnd: () -> Unit,
    onToggle: () -> Unit, onStar: () -> Unit, onOpen: () -> Unit,
) {
    val look = LocalLook.current
    val shape = RoundedCornerShape(22.dp)
    var handleTop by remember { mutableFloatStateOf(0f) }
    val currentStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentEnd by rememberUpdatedState(onDragEnd)

    val overdue = !t.done && t.dueAt != null && dateOf(t.dueAt).isBefore(today)
    val sub = buildList {
        t.dueAt?.let { ms ->
            val d = dateOf(ms)
            val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            val day = when {
                d == today -> "Today"
                d == today.plusDays(1) -> "Tomorrow"
                d.isBefore(today) -> "Overdue, $d"
                else -> d.toString()
            }
            add("$day ${Fmt.time(z.hour, z.minute, h12)}")
        }
        if (t.repeatRule != 0) add(listOf("", "Daily", "Weekly", "Monthly")[t.repeatRule])
        if (t.reminder) add("Reminder")
    }.joinToString("  -  ")

    Box(
        Modifier.fillMaxWidth()
            .onSizeChanged { onHeight(it.height) }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = offsetY; scaleX = if (dragging) 1.02f else 1f; scaleY = if (dragging) 1.02f else 1f }
            .padding(bottom = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .shadow(if (dragging) 14.dp else 0.dp, shape)
                .clip(shape)
                .background(if (dragging) Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface)) else Brush.verticalGradient(listOf(look.cardTop, look.cardBottom)))
                .border(1.dp, Brush.linearGradient(listOf(look.borderHi, look.borderLo)), shape)
                .clickable(onClick = onOpen)
                .padding(start = 4.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canDrag) {
                Box(
                    Modifier.size(width = 40.dp, height = 52.dp)
                        .onGloballyPositioned { handleTop = it.positionInWindow().y }
                        .pointerInput(t.id) {
                            detectDragGestures(
                                onDragStart = { offset -> currentStart(handleTop + offset.y) },
                                onDrag = { change, amount -> change.consume(); currentDrag(amount.y) },
                                onDragEnd = { currentEnd() },
                                onDragCancel = { currentEnd() },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.DragHandle, "Hold and drag to reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp)) }
            } else Spacer(Modifier.width(8.dp))
            RoundCheck(t.done, onToggle)
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    t.title, style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                )
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onStar, modifier = Modifier.size(52.dp)) {
                Icon(
                    if (t.priority == 2) Icons.Default.Star else Icons.Outlined.StarBorder,
                    if (t.priority == 2) "High priority, tap to remove" else "Mark as high priority",
                    tint = if (t.priority == 2) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditor(t: Task, h12: Boolean, today: LocalDate, onDismiss: () -> Unit, onSave: (Task) -> Unit, onDelete: () -> Unit) {
    var title by remember(t.id) { mutableStateOf(t.title) }
    var notes by remember(t.id) { mutableStateOf(t.notes) }
    var high by remember(t.id) { mutableStateOf(t.priority == 2) }
    var repeat by remember(t.id) { mutableIntStateOf(t.repeatRule) }
    var reminder by remember(t.id) { mutableStateOf(t.reminder) }
    var date by remember(t.id) { mutableStateOf(t.dueAt?.let { dateOf(it) }) }
    var minutes by remember(t.id) { mutableIntStateOf(t.dueAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).let { z -> z.hour * 60 + z.minute } } ?: 540) }
    var dateDialog by remember { mutableStateOf(false) }
    var timeDialog by remember { mutableStateOf(false) }

    EditorDialog(
        title = "Task",
        onDismiss = onDismiss,
        footer = {
            BigButton("Cancel", onDismiss, Modifier.weight(1f), BtnStyle.Outline, height = 60.dp)
            BigButton("Save", {
                val due = date?.atTime(LocalTime.of(minutes / 60, minutes % 60))?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                onSave(t.copy(
                    title = title.trim(), notes = notes.trim(), priority = if (high) 2 else 1,
                    repeatRule = if (due != null) repeat else 0, reminder = reminder && due != null, dueAt = due,
                ))
            }, Modifier.weight(1f), enabled = title.isNotBlank(), height = 60.dp)
        },
    ) {
        BuddyTextField(title, { title = it }, "Title", singleLine = false)
        BuddyTextField(notes, { notes = it }, "Notes (optional)", singleLine = false, minLines = 2)
        CardBox { SwitchRow("High priority (star)", "Starred tasks stay at the top of the list.", high) { high = it } }

        CardBox {
            Text("Due", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    date?.let { "$it  ${Fmt.minutes(minutes, h12)}" } ?: "No due date",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                BigButton(if (date == null) "Set date" else "Date", { dateDialog = true }, style = BtnStyle.Tonal, height = 48.dp)
            }
            if (date != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    BigButton("Time", { timeDialog = true }, Modifier.weight(1f), BtnStyle.Tonal, height = 48.dp)
                    BigButton("Clear", { date = null; reminder = false; repeat = 0 }, Modifier.weight(1f), BtnStyle.Text, height = 48.dp)
                }
                Spacer(Modifier.height(12.dp))
                ChipFlow {
                    listOf("No repeat", "Daily", "Weekly", "Monthly").forEachIndexed { i, n -> BuddyChip(repeat == i, n, { repeat = i }) }
                }
                SwitchRow("Remind me at the due time", checked = reminder) { reminder = it }
            }
        }
        BigButton("Delete task", onDelete, Modifier.fillMaxWidth(), BtnStyle.Danger)
        Spacer(Modifier.height(8.dp))
    }

    if (dateDialog) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { dateDialog = false },
            confirmButton = { BigButton("OK", { state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; dateDialog = false }, height = 48.dp) },
            dismissButton = { BigButton("Cancel", { dateDialog = false }, style = BtnStyle.Text, height = 48.dp) },
        ) { DatePicker(state = state) }
    }
    if (timeDialog) TimeDialog(minutes / 60, minutes % 60, !h12, { timeDialog = false }, { hh, mm -> minutes = hh * 60 + mm; timeDialog = false }, "Due time")
}
