package com.arghya.buddyonthego.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.data.Task
import com.arghya.buddyonthego.reminder.ReminderScheduler
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TasksScreen(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val dao = ServiceLocator.db.taskDao()
    val all by dao.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val h12 = rememberBool(K.H12, true)
    val today = LocalDate.now()
    var quick by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var onlyHigh by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Task?>(null) }

    val filtered = remember(all, search, onlyHigh) {
        all.filter { (search.isBlank() || it.title.contains(search, true) || it.notes.contains(search, true)) && (!onlyHigh || it.priority == 2) }
    }
    val todayList = remember(filtered, today) { filtered.filter { !it.done && it.dueAt != null && !dateOf(it.dueAt).isAfter(today) } }
    val upcoming = remember(filtered, today) { filtered.filter { !it.done && (it.dueAt == null || dateOf(it.dueAt).isAfter(today)) } }
    val done = remember(filtered) { filtered.filter { it.done } }
    val pad = listPadding()

    fun complete(t: Task, checked: Boolean) {
        scope.launch {
            if (checked) {
                dao.upsert(t.copy(done = true, doneAt = System.currentTimeMillis()))
                ReminderScheduler.cancelTask(ctx, t.id)
                if (t.repeatRule != 0 && t.dueAt != null) { // repeat: create the next occurrence
                    val z = Instant.ofEpochMilli(t.dueAt).atZone(ZoneId.systemDefault())
                    val next = when (t.repeatRule) { 1 -> z.plusDays(1); 2 -> z.plusWeeks(1); else -> z.plusMonths(1) }
                    val n = t.copy(id = 0, done = false, doneAt = null, dueAt = next.toInstant().toEpochMilli())
                    val id = dao.upsert(n)
                    if (ServiceLocator.settings.modTodo) ReminderScheduler.scheduleTask(ctx, n.copy(id = id))
                }
            } else {
                val u = t.copy(done = false, doneAt = null)
                dao.upsert(u)
                if (ServiceLocator.settings.modTodo) ReminderScheduler.scheduleTask(ctx, u)
            }
        }
    }

    @Composable
    fun TaskRow(t: Task) {
        Row(Modifier.fillMaxWidth().clickable { editing = t }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = t.done, onCheckedChange = { complete(t, it) })
            Column(Modifier.weight(1f)) {
                Text(t.title, textDecoration = if (t.done) TextDecoration.LineThrough else null, style = MaterialTheme.typography.bodyLarge)
                val sub = buildList {
                    t.dueAt?.let { ms ->
                        val z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
                        add("${dateOf(ms)} ${Fmt.time(z.hour, z.minute, h12)}")
                    }
                    if (t.priority == 2) add("High")
                    if (t.repeatRule != 0) add(listOf("", "Daily", "Weekly", "Monthly")[t.repeatRule])
                    if (t.reminder) add("Reminder")
                }.joinToString("  -  ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp, pad.calculateTopPadding(), 20.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                ScreenHeader("Tasks", "Get it done")
                OutlinedTextField(search, { search = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !onlyHigh, onClick = { onlyHigh = false }, label = { Text("All") })
                    FilterChip(selected = onlyHigh, onClick = { onlyHigh = true }, label = { Text("High priority") })
                }
            }
            if (all.isEmpty()) item { DisabledNote("Add a task below. Give it a date to get a reminder.") }
            if (todayList.isNotEmpty()) { item { SectionTitle("Today") }; items(todayList, key = { "t${it.id}" }) { TaskRow(it) } }
            if (upcoming.isNotEmpty()) { item { SectionTitle("Upcoming") }; items(upcoming, key = { "u${it.id}" }) { TaskRow(it) } }
            if (done.isNotEmpty()) { item { SectionTitle("Done") }; items(done, key = { "d${it.id}" }) { TaskRow(it) } }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = pad.calculateBottomPadding()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(quick, { quick = it }, placeholder = { Text("Quick add a task") }, singleLine = true, modifier = Modifier.weight(1f))
            FilledIconButton(enabled = quick.isNotBlank(), onClick = {
                val title = quick.trim(); quick = ""
                scope.launch { dao.upsert(Task(title = title)) }
            }) { Icon(Icons.Default.Add, "Add task") }
        }
    }

    editing?.let { t ->
        var title by remember(t.id) { mutableStateOf(t.title) }
        var notes by remember(t.id) { mutableStateOf(t.notes) }
        var priority by remember(t.id) { mutableIntStateOf(t.priority) }
        var repeat by remember(t.id) { mutableIntStateOf(t.repeatRule) }
        var reminder by remember(t.id) { mutableStateOf(t.reminder) }
        var date by remember(t.id) { mutableStateOf(t.dueAt?.let { dateOf(it) }) }
        var minutes by remember(t.id) { mutableIntStateOf(t.dueAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).let { z -> z.hour * 60 + z.minute } } ?: 540) }
        var dateDialog by remember { mutableStateOf(false) }
        var timeDialog by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Task") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") })
                    OutlinedTextField(notes, { notes = it }, label = { Text("Notes") })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Low", "Normal", "High").forEachIndexed { i, n -> FilterChip(selected = priority == i, onClick = { priority = i }, label = { Text(n) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(date?.let { "Due $it ${Fmt.time(minutes / 60, minutes % 60, h12)}" } ?: "No due date", Modifier.weight(1f))
                        TextButton(onClick = { dateDialog = true }) { Text("Date") }
                        if (date != null) TextButton(onClick = { timeDialog = true }) { Text("Time") }
                        if (date != null) TextButton(onClick = { date = null; reminder = false; repeat = 0 }) { Text("Clear") }
                    }
                    if (date != null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("No repeat", "Daily", "Weekly", "Monthly").forEachIndexed { i, n -> FilterChip(selected = repeat == i, onClick = { repeat = i }, label = { Text(n) }) }
                        }
                        SwitchRow("Remind me at the due time", checked = reminder) { reminder = it }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    editing = null
                    scope.launch {
                        val due = date?.atTime(LocalTime.of(minutes / 60, minutes % 60))?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                        val saved = t.copy(title = title.trim(), notes = notes.trim(), priority = priority, repeatRule = repeat, reminder = reminder && due != null, dueAt = due)
                        val id = dao.upsert(saved)
                        val withId = saved.copy(id = if (saved.id == 0L) id else saved.id)
                        if (ServiceLocator.settings.modTodo) ReminderScheduler.scheduleTask(ctx, withId)
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { editing = null; scope.launch { ReminderScheduler.cancelTask(ctx, t.id); dao.delete(t.id) } }) { Text("Delete") }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
        if (dateDialog) {
            val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { dateDialog = false },
                confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; dateDialog = false }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { dateDialog = false }) { Text("Cancel") } },
            ) { DatePicker(state = state) }
        }
        if (timeDialog) TimeDialog(minutes / 60, minutes % 60, !h12, { timeDialog = false }) { hh, mm -> minutes = hh * 60 + mm; timeDialog = false }
    }
}
