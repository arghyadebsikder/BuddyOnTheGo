package com.arghya.buddyonthego.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.gps.TripController
import com.arghya.buddyonthego.reminder.Reschedule
import com.arghya.buddyonthego.AppScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val confirmStop = mutableStateOf(false)
    private val shared = mutableStateOf<String?>(null)
    private var resumeRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.init(this)
        handle(intent)
        setContent {
            BuddyTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root(confirmStop.value, { confirmStop.value = false }, shared.value) { shared.value = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // D8: the "Resume trip?" notification opens the app, which restarts tracking while visible.
        if (resumeRequested) {
            resumeRequested = false
            TripController.resume(this)
        }
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        if (i.getBooleanExtra(EXTRA_CONFIRM_STOP, false)) confirmStop.value = true
        if (i.getBooleanExtra(EXTRA_RESUME, false)) resumeRequested = true
        if (i.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let { shared.value = it }
        if (i.action == Intent.ACTION_VIEW) i.dataString?.let { shared.value = it }
    }

    companion object {
        const val EXTRA_CONFIRM_STOP = "confirm_stop"
        const val EXTRA_RESUME = "resume_trip"
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    ALARMS("Alarms", Icons.Default.Alarm),
    PLACES("Places", Icons.Default.Place),
    HABITS("Habits", Icons.Default.CheckCircle),
    TASKS("Tasks", Icons.Default.TaskAlt),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
private fun Root(confirmStop: Boolean, dismissConfirm: () -> Unit, sharedText: String?, consumeShared: () -> Unit) {
    val ctx = LocalContext.current
    val onboarded = rememberBool(K.ONBOARDED, false)
    if (!onboarded) {
        Onboarding { ServiceLocator.settings.put(K.ONBOARDED, true) }
        return
    }
    val alarm = rememberBool(K.MOD_ALARM, true)
    val gps = rememberBool(K.MOD_GPS, true)
    val habit = rememberBool(K.MOD_HABIT, true)
    val todo = rememberBool(K.MOD_TODO, true)
    val tabs = Tab.values().filter {
        when (it) { Tab.ALARMS -> alarm; Tab.PLACES -> gps; Tab.HABITS -> habit; Tab.TASKS -> todo; Tab.SETTINGS -> true }
    }
    var selected by rememberSaveable { mutableStateOf(Tab.ALARMS.name) }
    val current = tabs.firstOrNull { it.name == selected } ?: tabs.first { it == Tab.SETTINGS }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(sharedText) { if (sharedText != null && gps) selected = Tab.PLACES.name }

    val look = LocalLook.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val topInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val pad = PaddingValues(top = topInset + 8.dp, bottom = bottomInset + 12.dp + 68.dp + 20.dp)

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(look.bgTop, look.bgBottom)))
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(look.glowA, Color.Transparent), center = Offset(size.width * 0.95f, 0f), radius = size.width * 0.95f))
                drawRect(Brush.radialGradient(listOf(look.glowB, Color.Transparent), center = Offset(0f, size.height * 0.85f), radius = size.width * 0.9f))
            },
    ) {
        CompositionLocalProvider(LocalPad provides pad) {
            Box(Modifier.fillMaxSize()) {
                when (current) {
                    Tab.ALARMS -> AlarmsScreen(snackbar)
                    Tab.PLACES -> PlacesScreen(snackbar, sharedText, consumeShared)
                    Tab.HABITS -> HabitsScreen()
                    Tab.TASKS -> TasksScreen(snackbar)
                    Tab.SETTINGS -> SettingsScreen(snackbar)
                }
            }
        }
        // Soft fade so content scrolls cleanly under the status bar and the floating bar.
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topInset + 20.dp)
                .background(Brush.verticalGradient(listOf(look.bgTop, Color.Transparent))),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomInset + 110.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, look.bgBottom))),
        )
        FloatingNav(tabs, current, { selected = it.name }, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 12.dp))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 96.dp))
    }

    if (confirmStop) AlertDialog(
        onDismissRequest = dismissConfirm,
        title = { Text("Stop tracking?") },
        text = { Text("Buddy will stop watching your trip.") },
        confirmButton = { TextButton(onClick = { dismissConfirm(); TripController.stop(ctx) }) { Text("Stop") } },
        dismissButton = { TextButton(onClick = dismissConfirm) { Text("Keep going") } },
    )
}

/** Five short, skippable screens (section 8.2). */
@Composable
private fun Onboarding(onDone: () -> Unit) {
    val s = ServiceLocator.settings
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val loc = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val online = rememberBool(K.ONLINE, false)

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Step ${step + 1} of 5", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            when (step) {
                0 -> {
                    Text("Buddy on the go", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text("Wakes you at the right time and at the right place.", style = MaterialTheme.typography.titleMedium)
                    Text("Alarm clock, GPS alarm, route guard, habits and to-dos. Every alarm works with no internet.")
                }
                1 -> {
                    Text("Pick your features", style = MaterialTheme.typography.headlineMedium)
                    SwitchRow("Alarm clock", checked = rememberBool(K.MOD_ALARM, true)) { s.put(K.MOD_ALARM, it) }
                    SwitchRow("GPS alarm and route guard", checked = rememberBool(K.MOD_GPS, true)) { s.put(K.MOD_GPS, it) }
                    SwitchRow("Habit tracker", checked = rememberBool(K.MOD_HABIT, true)) { s.put(K.MOD_HABIT, it) }
                    SwitchRow("To-do list", checked = rememberBool(K.MOD_TODO, true)) { s.put(K.MOD_TODO, it) }
                }
                2 -> {
                    Text("Online Mode", style = MaterialTheme.typography.headlineMedium)
                    Text("Online extras are optional. Alarms and tracking never use the internet.")
                    FilterChip(selected = !online, onClick = { s.put(K.ONLINE, false) }, label = { Text("Keep everything on my phone") })
                    FilterChip(selected = online, onClick = { s.put(K.ONLINE, true) }, label = { Text("Allow online extras (place search, short links)") })
                    Text("What leaves the phone: the text you search for and a pasted short link. Never GPS fixes, alarms, habits or tasks. No account, no analytics.", style = MaterialTheme.typography.bodySmall)
                }
                3 -> {
                    Text("Permissions", style = MaterialTheme.typography.headlineMedium)
                    Text("Notifications let Buddy show alarms and your trip progress.")
                    Button(onClick = { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
                    Text("Location is only used while a GPS alarm or route is counting down.")
                    Button(onClick = { loc.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Allow location") }
                    Text("You can skip these. Buddy asks again when it needs them.", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text("Ready", style = MaterialTheme.typography.headlineMedium)
                    Text("Open Settings, Reliability check, fix every red item, then run Test my setup. Follow the guide for your phone brand.")
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (step < 4) step++ else onDone() }) { Text("Skip") }
            Button(onClick = {
                if (step < 4) step++ else {
                    AppScope.io.launch { Reschedule.all(ctx) }
                    onDone()
                }
            }) { Text(if (step < 4) "Next" else "Start") }
        }
    }
}

@Composable
private fun FloatingNav(tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val shape = RoundedCornerShape(34.dp)
    Row(
        modifier.padding(horizontal = 14.dp).fillMaxWidth().height(68.dp)
            .shadow(20.dp, shape, ambientColor = look.accentB, spotColor = Color.Black)
            .clip(shape).background(look.navBg)
            .border(1.dp, Brush.linearGradient(listOf(look.borderHi, look.borderLo)), shape)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            val sel = t == current
            Row(
                Modifier.clip(RoundedCornerShape(26.dp))
                    .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(t) }
                    .padding(horizontal = if (sel) 12.dp else 10.dp, vertical = 10.dp)
                    .animateContentSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, t.label, tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                if (sel) {
                    Spacer(Modifier.width(5.dp))
                    Text(t.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
        }
    }
}
