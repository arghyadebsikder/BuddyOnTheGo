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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.arghya.buddyonthego.AppScope
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.gps.TripController
import com.arghya.buddyonthego.reminder.Reschedule
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

private val NAV_HEIGHT = 72.dp

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
    val density = LocalDensity.current
    val topInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    // When the keyboard is open the floating bar hides and the content sits right above the keyboard (imePadding below).
    val ime = WindowInsets.ime
    val imeOpen by remember { derivedStateOf { ime.getBottom(density) > 0 } }
    val pad = PaddingValues(
        top = topInset + 8.dp,
        bottom = if (imeOpen) 12.dp else bottomInset + 12.dp + NAV_HEIGHT + 20.dp,
    )

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(look.bgTop, look.bgBottom)))
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(look.glowA, Color.Transparent), center = Offset(size.width * 0.95f, 0f), radius = size.width * 0.95f))
                drawRect(Brush.radialGradient(listOf(look.glowB, Color.Transparent), center = Offset(0f, size.height * 0.85f), radius = size.width * 0.9f))
            },
    ) {
        CompositionLocalProvider(LocalPad provides pad) {
            Box(Modifier.fillMaxSize().imePadding()) {
                when (current) {
                    Tab.ALARMS -> AlarmsScreen(snackbar)
                    Tab.PLACES -> PlacesScreen(snackbar, sharedText, consumeShared)
                    Tab.HABITS -> HabitsScreen(snackbar)
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
        if (!imeOpen) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomInset + 120.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, look.bgBottom))),
            )
            FloatingNav(tabs, current, { selected = it.name }, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 12.dp))
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = if (imeOpen) 12.dp else bottomInset + NAV_HEIGHT + 24.dp).imePadding(),
        )
    }

    if (confirmStop) AlertDialog(
        onDismissRequest = dismissConfirm,
        title = { Text("Stop tracking?") },
        text = { Text("Buddy will stop watching your trip.", style = MaterialTheme.typography.bodyLarge) },
        confirmButton = { BigButton("Stop", { dismissConfirm(); TripController.stop(ctx) }, style = BtnStyle.Danger, height = 48.dp) },
        dismissButton = { BigButton("Keep going", dismissConfirm, style = BtnStyle.Text, height = 48.dp) },
    )
}

// ---------------------------------------------------------------------------------------------
// Startup
// ---------------------------------------------------------------------------------------------

private const val LAST_STEP = 4

/** Five short screens. Content is centred on the screen; the buttons stay at the bottom. */
@Composable
private fun Onboarding(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val look = LocalLook.current
    var step by rememberSaveable { mutableIntStateOf(0) }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(look.bgTop, look.bgBottom)))
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(look.glowA, Color.Transparent), center = Offset(size.width * 0.5f, size.height * 0.28f), radius = size.width * 1.1f))
                drawRect(Brush.radialGradient(listOf(look.glowB, Color.Transparent), center = Offset(size.width * 0.1f, size.height * 0.95f), radius = size.width * 0.9f))
            },
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StepDots(step)
                if (step < LAST_STEP) BigButton("Skip", { step = LAST_STEP }, style = BtnStyle.Text, height = 48.dp)
            }

            // The page sits in the middle of the free space.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(150)) },
                    label = "onboarding",
                ) { s ->
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        when (s) {
                            0 -> WelcomePage()
                            1 -> FeaturesPage()
                            2 -> OnlinePage()
                            3 -> PermissionsPage()
                            else -> ReadyPage()
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step > 0) BigButton("Back", { step-- }, Modifier.weight(0.45f), BtnStyle.Outline, height = 60.dp)
                BigButton(
                    when (step) { 0 -> "Get started"; LAST_STEP -> "Start using Buddy"; else -> "Next" },
                    {
                        if (step < LAST_STEP) step++ else {
                            AppScope.io.launch { Reschedule.all(ctx) }
                            onDone()
                        }
                    },
                    Modifier.weight(1f), height = 60.dp,
                )
            }
        }
    }
}

@Composable
private fun StepDots(step: Int) {
    val look = LocalLook.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(LAST_STEP + 1) { i ->
            val w by animateDpAsState(if (i == step) 28.dp else 9.dp, spring(), label = "dot")
            Box(
                Modifier.height(9.dp).width(w).clip(CircleShape)
                    .background(if (i <= step) Brush.horizontalGradient(listOf(look.accentA, look.accentB)) else Brush.horizontalGradient(listOf(look.borderHi.copy(alpha = 0.35f), look.borderHi.copy(alpha = 0.35f)))),
            )
        }
    }
}

@Composable
private fun BuddyLogo(size: Int = 128) {
    val look = LocalLook.current
    val t = rememberInfiniteTransition(label = "logo")
    val pulse by t.animateFloat(1f, 1.14f, infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val glow by t.animateFloat(0.25f, 0.7f, infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    Box(Modifier.size((size * 1.5f).dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size.dp).graphicsLayer { scaleX = pulse * 1.15f; scaleY = pulse * 1.15f; alpha = glow }
                .clip(CircleShape).background(Brush.radialGradient(listOf(look.accentA, Color.Transparent))),
        )
        Box(
            Modifier.size(size.dp).shadow(24.dp, CircleShape, ambientColor = look.accentA, spotColor = look.accentB)
                .clip(CircleShape).background(Brush.linearGradient(listOf(look.btnA, look.btnB))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Alarm, null, tint = look.onBtn, modifier = Modifier.size((size * 0.52f).dp)) }
    }
}

@Composable
private fun WelcomePage() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    @Composable
    fun rise(delay: Int, content: @Composable () -> Unit) =
        AnimatedVisibility(shown, enter = fadeIn(tween(700, delay)) + slideInVertically(tween(700, delay)) { it / 5 }) { content() }

    rise(0) { BuddyLogo() }
    Spacer(Modifier.height(8.dp))
    rise(150) { GradientText("Buddy on the go", 40, FontWeight.Light) }
    Spacer(Modifier.height(12.dp))
    rise(300) {
        Text(
            "Wakes you at the right time\nand at the right place.",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal), textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(10.dp))
    rise(450) {
        Text(
            "Alarms, GPS alarms, habits and tasks. Every alarm works with no internet.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String? = null) {
    Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    if (subtitle != null) {
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun FeaturesPage() {
    val s = ServiceLocator.settings
    PageTitle("Pick your tools", "You can change this any time in Settings.")
    CardBox {
        SwitchRow("Alarm clock", checked = rememberBool(K.MOD_ALARM, true)) { s.put(K.MOD_ALARM, it) }
        SwitchRow("GPS alarm and route guard", checked = rememberBool(K.MOD_GPS, true)) { s.put(K.MOD_GPS, it) }
        SwitchRow("Habit tracker", checked = rememberBool(K.MOD_HABIT, true)) { s.put(K.MOD_HABIT, it) }
        SwitchRow("To-do list", checked = rememberBool(K.MOD_TODO, true)) { s.put(K.MOD_TODO, it) }
    }
}

@Composable
private fun OptionCard(selected: Boolean, title: String, text: String, onClick: () -> Unit) {
    val look = LocalLook.current
    val shape = RoundedCornerShape(24.dp)
    val borderBrush = if (selected) Brush.linearGradient(listOf(look.accentA, look.accentB)) else Brush.linearGradient(listOf(look.borderHi, look.borderLo))
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(look.cardTop, look.cardBottom)))
            .border(if (selected) 2.dp else 1.dp, borderBrush, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Default.CheckCircle, "Selected", tint = look.success, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun OnlinePage() {
    val s = ServiceLocator.settings
    val online = rememberBool(K.ONLINE, false)
    PageTitle("Online extras", "Alarms and tracking never use the internet.")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OptionCard(!online, "Keep everything on my phone", "Recommended. Nothing is sent anywhere.") { s.put(K.ONLINE, false) }
        OptionCard(online, "Allow online extras", "Place search and short map links.") { s.put(K.ONLINE, true) }
    }
    Spacer(Modifier.height(14.dp))
    Text(
        "Only the text you search for and a pasted short link would leave the phone. Never GPS fixes, alarms, habits or tasks. No account, no analytics.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
    )
}

@Composable
private fun PermissionCard(
    icon: ImageVector, title: String, why: String,
    granted: Boolean, blocked: Boolean, action: String, onAllow: () -> Unit, onOpenSettings: () -> Unit,
    note: String? = null,
) {
    val look = LocalLook.current
    val haptic = LocalHapticFeedback.current
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(granted) {
        if (first) first = false else if (granted) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    CardBox(accent = granted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(if (granted) look.success.copy(alpha = 0.18f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = if (granted) look.success else MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        AnimatedVisibility(granted, enter = fadeIn() + scaleIn(spring(dampingRatio = 0.5f))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = look.success, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text("Allowed. All set.", style = MaterialTheme.typography.titleSmall.copy(fontSize = 17.sp), color = look.success)
            }
        }
        if (!granted) {
            if (blocked) {
                Text(
                    "Android will not ask again. Open Settings and turn it on.",
                    style = MaterialTheme.typography.bodySmall, color = look.warning, modifier = Modifier.padding(bottom = 8.dp),
                )
                BigButton("Open Settings", onOpenSettings, Modifier.fillMaxWidth(), BtnStyle.Tonal)
            } else BigButton(action, onAllow, Modifier.fillMaxWidth())
        }
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = look.warning, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun PermissionsPage() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    // Check again when the user comes back from the system Settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    val notifOn = remember(refresh) { Perms.notificationsOn(ctx) }
    val notifBlocked = remember(refresh) { Perms.notificationsBlocked(ctx) }
    val locOn = remember(refresh) { Perms.locationOn(ctx) }
    val locBlocked = remember(refresh) { Perms.locationBlocked(ctx) }
    val gpsSwitchOn = remember(refresh) { Perms.locationServicesOn(ctx) }

    PageTitle("Two quick permissions", "You can skip these. Buddy asks again when it needs them.")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PermissionCard(
            Icons.Default.Notifications, "Notifications", "Shows your alarms and trip progress.",
            granted = notifOn, blocked = notifBlocked, action = "Allow notifications",
            onAllow = {
                if (Build.VERSION.SDK_INT >= 33) {
                    Perms.markAskedNotifications()
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else Perms.openNotificationSettings(ctx)
            },
            onOpenSettings = { Perms.openNotificationSettings(ctx) },
        )
        PermissionCard(
            Icons.Default.LocationOn, "Location", "Only used while a GPS alarm or route is counting down.",
            granted = locOn, blocked = locBlocked, action = "Allow location",
            onAllow = {
                Perms.markAskedLocation()
                locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            onOpenSettings = { Perms.openAppSettings(ctx) },
            note = if (locOn && !gpsSwitchOn) "Location is switched off on this phone. Turn it on in the quick settings for GPS alarms to work." else null,
        )
        if (locOn && !gpsSwitchOn) BigButton("Open location settings", { Perms.openLocationSettings(ctx) }, Modifier.fillMaxWidth(), BtnStyle.Outline)
    }
}

@Composable
private fun ReadyPage() {
    val ctx = LocalContext.current
    val look = LocalLook.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val notifOn = remember(refresh) { Perms.notificationsOn(ctx) }
    val locOn = remember(refresh) { Perms.locationOn(ctx) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    AnimatedVisibility(shown, enter = scaleIn(spring(dampingRatio = 0.45f)) + fadeIn()) {
        Box(
            Modifier.size(112.dp).clip(CircleShape).background(Brush.linearGradient(listOf(look.btnA, look.btnB))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.CheckCircle, null, tint = look.onBtn, modifier = Modifier.size(64.dp)) }
    }
    Spacer(Modifier.height(20.dp))
    PageTitle("You are all set", "One last step: open Settings, Reliability check, and fix any red item so alarms never miss.")
    CardBox {
        StatusLine("Notifications", notifOn)
        StatusLine("Location", locOn)
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    val look = LocalLook.current
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(if (ok) look.success else look.warning))
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(if (ok) "On" else "Off, you can turn it on later", style = MaterialTheme.typography.bodySmall, color = if (ok) look.success else look.warning)
    }
}

// ---------------------------------------------------------------------------------------------
// Bottom bar
// ---------------------------------------------------------------------------------------------

@Composable
private fun FloatingNav(tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalLook.current
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(36.dp)
    Row(
        modifier.padding(horizontal = 14.dp).fillMaxWidth().height(NAV_HEIGHT)
            .shadow(20.dp, shape, ambientColor = look.accentB, spotColor = Color.Black)
            .clip(shape).background(look.navBg)
            .border(1.dp, Brush.linearGradient(listOf(look.borderHi, look.borderLo)), shape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            val sel = t == current
            val source = remember { MutableInteractionSource() }
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(28.dp))
                    .pressScale(source, 0.93f)
                    .background(if (sel) Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable(interactionSource = source, indication = null, role = Role.Tab) {
                        if (!sel) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(t)
                    },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(t.icon, null, tint = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(if (sel) 28.dp else 26.dp))
                Text(
                    t.label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
