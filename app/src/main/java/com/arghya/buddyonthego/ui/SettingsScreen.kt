package com.arghya.buddyonthego.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AS
import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arghya.buddyonthego.AppScope
import com.arghya.buddyonthego.BuildConfig
import com.arghya.buddyonthego.domain.ThemeSchedule
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.alarm.AlarmScheduler
import com.arghya.buddyonthego.data.Alarm
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.net.Feature
import com.arghya.buddyonthego.reminder.Reschedule
import kotlinx.coroutines.launch
import java.time.LocalDateTime

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val s = ServiceLocator.settings
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val online = rememberBool(K.ONLINE, false)
    var importDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var recheck by remember { mutableIntStateOf(0) }

    fun module(key: String, on: Boolean) {
        s.put(key, on)
        AppScope.io.launch { Reschedule.all(ctx) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding()) {
        item(key = "header") { ScreenHeader("Settings", "Make it yours") }

        item {
            SectionTitle("Connectivity")
            CardBox {
            SwitchRow("Online Mode", "Off: Buddy makes zero network requests. Alarms never need the internet.", online) { s.put(K.ONLINE, it) }
            SwitchRow("Place search", checked = rememberBool(K.ON_SEARCH, true), enabled = online) { s.put(K.ON_SEARCH, it) }
            SwitchRow("Resolve short map links", checked = rememberBool(K.ON_LINKS, true), enabled = online) { s.put(K.ON_LINKS, it) }
            SwitchRow("Wi-Fi only for large downloads", checked = rememberBool(K.WIFI_ONLY, true)) { s.put(K.WIFI_ONLY, it) }
            if (!online) DisabledNote("Turn on Online Mode to use the switches above.")
            Text("Data used this month", style = MaterialTheme.typography.titleSmall)
            Feature.values().forEach { f -> Text("${f.label}: ${ServiceLocator.gate.bytesThisMonth(f) / 1024} KB", style = MaterialTheme.typography.bodySmall) }
            BigButton("Reset counters", { ServiceLocator.gate.resetCounters(); recheck++ }, style = BtnStyle.Text, height = 48.dp)
            DisabledNote("Only these leave the phone: text you search for and a pasted short link. Never GPS fixes, alarms, habits or tasks.")
            }
        }

        item {
            SectionTitle("Features")
            CardBox {
            SwitchRow("Alarm clock", checked = rememberBool(K.MOD_ALARM, true)) { module(K.MOD_ALARM, it) }
            SwitchRow("GPS alarm and route guard", checked = rememberBool(K.MOD_GPS, true)) { module(K.MOD_GPS, it) }
            SwitchRow("Habit tracker", checked = rememberBool(K.MOD_HABIT, true)) { module(K.MOD_HABIT, it) }
            SwitchRow("To-do list", checked = rememberBool(K.MOD_TODO, true)) { module(K.MOD_TODO, it) }
            DisabledNote("Turning a feature off hides it and cancels its alarms. Your data stays saved.")
            }
        }

        item {
            SectionTitle("Appearance and units")
            CardBox {
            val theme = rememberInt(K.THEME, 0)
            val h12 = rememberBool(K.H12, true)
            val dayStart = rememberInt(K.DAY_START, ThemeSchedule.DEFAULT_DAY)
            val nightStart = rememberInt(K.NIGHT_START, ThemeSchedule.DEFAULT_NIGHT)
            var pickDay by remember { mutableStateOf(false) }
            var pickNight by remember { mutableStateOf(false) }
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            ChipFlow {
                listOf("Device", "Light", "Dark", "Auto").forEachIndexed { i, n -> BuddyChip(theme == i, n, { s.put(K.THEME, i) }) }
            }
            Text(
                when (theme) {
                    1 -> "Always light."
                    2 -> "Always dark."
                    3 -> "Light by day, dark at night. Switches by itself at the times below."
                    else -> "Follows your phone's dark mode."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
            if (theme == 3) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Day starts", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    BigButton(Fmt.minutes(dayStart, h12), { pickDay = true }, style = BtnStyle.Tonal, height = 48.dp)
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Night starts", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    BigButton(Fmt.minutes(nightStart, h12), { pickNight = true }, style = BtnStyle.Tonal, height = 48.dp)
                }
            }
            if (pickDay) TimeDialog(dayStart / 60, dayStart % 60, !h12, { pickDay = false }, { hh, mm -> s.put(K.DAY_START, hh * 60 + mm); pickDay = false }, "Day starts")
            if (pickNight) TimeDialog(nightStart / 60, nightStart % 60, !h12, { pickNight = false }, { hh, mm -> s.put(K.NIGHT_START, hh * 60 + mm); pickNight = false }, "Night starts")
            SwitchRow("Dynamic color (Android 12+)", "Off keeps the indigo and amber look", rememberBool(K.DYNAMIC, false), Build.VERSION.SDK_INT >= 31) { s.put(K.DYNAMIC, it) }
            SwitchRow("Distance in kilometers", "Off shows miles", rememberBool(K.UNITS_KM, true)) { s.put(K.UNITS_KM, it) }
            SwitchRow("12-hour clock", checked = rememberBool(K.H12, true)) { s.put(K.H12, it) }
            }
        }

        item {
            SectionTitle("Alarms")
            CardBox {
            SwitchRow(
                "Open alarm full screen", "On: the alarm covers the screen. Off: it shows as a small banner you can tap.",
                rememberBool(K.FULLSCREEN_ALARM, true),
            ) { s.put(K.FULLSCREEN_ALARM, it) }
            Stepper("Ring auto-stop", rememberInt(K.AUTOSTOP_MIN, 10), 1, 30, 1, " min") { s.put(K.AUTOSTOP_MIN, it) }
            Stepper("Re-alert every", rememberInt(K.REALERT_SEC, 60), 15, 300, 15, " s") { s.put(K.REALERT_SEC, it) }
            }
        }

        item {
            SectionTitle("GPS and routes")
            CardBox {
            val mode = rememberInt(K.GPS_MODE, 1)
            ChipFlow {
                listOf("Battery saver", "Balanced", "Precise").forEachIndexed { i, n -> BuddyChip(mode == i, n, { s.put(K.GPS_MODE, i) }) }
            }
            SwitchRow("Wrong-direction alarm", checked = rememberBool(K.WRONG_DIR, true)) { s.put(K.WRONG_DIR, it) }
            Stepper("GPS lost: quiet warning", rememberInt(K.LOST_QUIET, 3), 1, 30, 1, " min") { s.put(K.LOST_QUIET, it) }
            Stepper("GPS lost: ring after", rememberInt(K.LOST_RING, 15), 3, 60, 1, " min") { s.put(K.LOST_RING, it) }
            SwitchRow("Backup timer and watchdog", "Wakes you if tracking dies", rememberBool(K.BACKUP_TIMER, true)) { s.put(K.BACKUP_TIMER, it) }
            SwitchRow("Silent auto-resume after reboot (experimental)", "Not in this build. After a reboot, tap the Resume trip notification.", false, enabled = false) { }
            }
        }

        item {
            SectionTitle("Habits")
            CardBox {
            SwitchRow("One-day streak grace", checked = rememberBool(K.STREAK_GRACE, false)) { s.put(K.STREAK_GRACE, it) }
            val dndOn = remember(recheck) { com.arghya.buddyonthego.reminder.SoundGuard.hasPolicyAccess(ctx) }
            Text(
                if (dndOn) "Silent mode for timed tasks: allowed." else "Silent mode for timed tasks needs Do Not Disturb access. Without it, Silent only vibrates.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!dndOn) BigButton("Allow Do Not Disturb access", {
                try { ctx.startActivity(Intent(AS.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            }, Modifier.fillMaxWidth().padding(top = 8.dp), BtnStyle.Tonal)
            }
        }

        item {
            SectionTitle("Reliability check")
            recheck.hashCode()
            ReliabilityCard(ctx) { recheck++ }
            BigButton("Test my setup (alarm in 1 minute)", onClick = {
                scope.launch {
                    val dao = ServiceLocator.db.alarmDao()
                    val t = LocalDateTime.now().plusMinutes(1)
                    val a = Alarm(hour = t.hour, minute = t.minute, daysMask = 0, label = "Test my setup", volumeRampSeconds = 5, maxSnoozes = 0)
                    val id = dao.upsert(a)
                    AlarmScheduler.schedule(ctx, a.copy(id = id))
                    snackbar.showSnackbar("Test alarm in about 1 minute. Lock your phone now.")
                }
            }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            BrandGuide()
        }

        item {
            SectionTitle("Data and backup")
            CardBox {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                BigButton("Copy backup", onClick = {
                    scope.launch {
                        clipboard.setText(AnnotatedString(Backup.export()))
                        snackbar.showSnackbar("Backup copied to the clipboard.")
                    }
                }, modifier = Modifier.weight(1f), style = BtnStyle.Outline)
                BigButton("Share backup", onClick = {
                    scope.launch {
                        val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, Backup.export()).putExtra(Intent.EXTRA_SUBJECT, "Buddy backup.json")
                        ctx.startActivity(Intent.createChooser(send, "Share backup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }, modifier = Modifier.weight(1f), style = BtnStyle.Outline)
            }
            Spacer(Modifier.height(8.dp))
            BigButton("Import from clipboard", { importText = clipboard.getText()?.text.orEmpty(); importDialog = true }, Modifier.fillMaxWidth(), BtnStyle.Outline)
            }
        }

        item {
            SectionTitle("About")
            CardBox {
            Text("Buddy on the go v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            DisabledNote("Map data (when Online Mode is on) is from OpenStreetMap contributors. Privacy: Buddy has no account, no analytics and no ads. Nothing about your trips or alarms leaves your phone.")
            }
        }
    }

    if (importDialog) AlertDialog(
        onDismissRequest = { importDialog = false },
        title = { Text("Import backup") },
        text = { Text("Add everything from the backup on the clipboard (${importText.length} characters) to this phone?") },
        confirmButton = {
            BigButton("Import", onClick = {
                importDialog = false
                scope.launch {
                    val msg = try { "Imported ${Backup.import(importText)} items." } catch (e: Exception) { "That is not a valid Buddy backup." }
                    Reschedule.all(ctx)
                    snackbar.showSnackbar(msg)
                }
            }, height = 48.dp)
        },
        dismissButton = { BigButton("Cancel", { importDialog = false }, style = BtnStyle.Text, height = 48.dp) },
    )
}

private data class Check(val title: String, val ok: Boolean, val fixLabel: String, val fix: () -> Unit)

@Composable
private fun ReliabilityCard(ctx: Context, onChanged: () -> Unit) {
    val am = ctx.getSystemService(AlarmManager::class.java)
    val pm = ctx.getSystemService(PowerManager::class.java)
    val look = LocalLook.current
    var tick by remember { mutableIntStateOf(0) }
    // Check again every time the person comes back from a system settings page, so the dot turns green by itself.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++; onChanged() }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        if (!granted) Perms.openNotificationSettings(ctx)
    }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        tick++
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] != true) Perms.openAppSettings(ctx)
    }
    fun go(i: Intent) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { Perms.openAppSettings(ctx) }
    }
    tick.hashCode()
    val checks = listOf(
        Check("Notifications allowed", Perms.notificationsOn(ctx), "Allow") {
            if (Build.VERSION.SDK_INT >= 33 && !Perms.notificationsBlocked(ctx)) {
                Perms.markAskedNotifications()
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else Perms.openNotificationSettings(ctx)
        },
        Check("Exact alarms allowed", Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms(), "Fix") { if (Build.VERSION.SDK_INT >= 31) go(Intent(AS.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) },
        Check("Full-screen alerts allowed", Build.VERSION.SDK_INT < 34 || ctx.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent(), "Fix") { if (Build.VERSION.SDK_INT >= 34) go(Intent(AS.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))) },
        Check("Location permission", Perms.locationOn(ctx), "Allow") {
            if (Perms.locationBlocked(ctx)) Perms.openAppSettings(ctx) else {
                Perms.markAskedLocation()
                locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        },
        Check("Location switched on", Perms.locationServicesOn(ctx), "Open") { Perms.openLocationSettings(ctx) },
        Check("Battery optimization exempt", pm.isIgnoringBatteryOptimizations(ctx.packageName), "Fix") { go(Intent(AS.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) },
        Check("Phone battery saver off", !pm.isPowerSaveMode, "Open") { go(Intent(AS.ACTION_BATTERY_SAVER_SETTINGS)) },
    )
    CardBox {
        val good = checks.count { it.ok }
        Text("$good of ${checks.size} ready", style = MaterialTheme.typography.titleMedium, color = if (good == checks.size) look.success else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        checks.forEach { c ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp).background(if (c.ok) look.success else look.danger, CircleShape))
                Spacer(Modifier.width(12.dp))
                Text(c.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (!c.ok) BigButton(c.fixLabel, { c.fix() }, style = BtnStyle.Tonal, height = 44.dp)
            }
        }
    }
}

@Composable
private fun BrandGuide() {
    val brand = Build.MANUFACTURER.lowercase()
    val text = when {
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
            "Xiaomi / Redmi / HyperOS: turn on Autostart for Buddy, set Battery saver to No restrictions, and lock Buddy in the recents screen."
        brand.contains("samsung") -> "Samsung: remove Buddy from Sleeping apps and turn off Adaptive battery for it."
        brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
            "Oppo / Realme / OnePlus: allow Auto-launch and Background activity for Buddy, and set battery usage to Don't optimize."
        brand.contains("vivo") || brand.contains("iqoo") -> "Vivo: allow High background power consumption and Autostart for Buddy."
        brand.contains("huawei") || brand.contains("honor") -> "Huawei / Honor: set Buddy to Manage manually and allow Auto-launch, Secondary launch and Run in background."
        else -> "Allow Buddy to run in the background and exclude it from battery optimization."
    }
    CardBox { Text("Phone guide (${Build.MANUFACTURER})", style = MaterialTheme.typography.titleMedium); Text(text, style = MaterialTheme.typography.bodyLarge) }
}
