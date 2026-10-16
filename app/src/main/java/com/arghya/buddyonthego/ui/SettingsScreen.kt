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
            Text("Data used this month", style = MaterialTheme.typography.labelLarge)
            Feature.values().forEach { f -> Text("${f.label}: ${ServiceLocator.gate.bytesThisMonth(f) / 1024} KB", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { ServiceLocator.gate.resetCounters(); recheck++ }) { Text("Reset counters") }
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("System", "Light", "Dark").forEachIndexed { i, n -> FilterChip(selected = theme == i, onClick = { s.put(K.THEME, i) }, label = { Text(n) }) }
            }
            SwitchRow("Dynamic color (Android 12+)", "Off keeps the indigo and amber look", rememberBool(K.DYNAMIC, false), Build.VERSION.SDK_INT >= 31) { s.put(K.DYNAMIC, it) }
            SwitchRow("Distance in kilometers", "Off shows miles", rememberBool(K.UNITS_KM, true)) { s.put(K.UNITS_KM, it) }
            SwitchRow("12-hour clock", checked = rememberBool(K.H12, true)) { s.put(K.H12, it) }
            }
        }

        item {
            SectionTitle("Alarms")
            CardBox {
            Stepper("Ring auto-stop", rememberInt(K.AUTOSTOP_MIN, 10), 1, 30, 1, " min") { s.put(K.AUTOSTOP_MIN, it) }
            Stepper("Re-alert every", rememberInt(K.REALERT_SEC, 60), 15, 300, 15, " s") { s.put(K.REALERT_SEC, it) }
            }
        }

        item {
            SectionTitle("GPS and routes")
            CardBox {
            val mode = rememberInt(K.GPS_MODE, 1)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Battery saver", "Balanced", "Precise").forEachIndexed { i, n -> FilterChip(selected = mode == i, onClick = { s.put(K.GPS_MODE, i) }, label = { Text(n) }) }
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
            }
        }

        item {
            SectionTitle("Reliability check")
            recheck.hashCode()
            ReliabilityCard(ctx) { recheck++ }
            Button(onClick = {
                scope.launch {
                    val dao = ServiceLocator.db.alarmDao()
                    val t = LocalDateTime.now().plusMinutes(1)
                    val a = Alarm(hour = t.hour, minute = t.minute, daysMask = 0, label = "Test my setup", volumeRampSeconds = 5, maxSnoozes = 0)
                    val id = dao.upsert(a)
                    AlarmScheduler.schedule(ctx, a.copy(id = id))
                    snackbar.showSnackbar("Test alarm in about 1 minute. Lock your phone now.")
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Test my setup (alarm in 1 minute)") }
            Spacer(Modifier.height(8.dp))
            BrandGuide()
        }

        item {
            SectionTitle("Data and backup")
            CardBox {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    scope.launch {
                        clipboard.setText(AnnotatedString(Backup.export()))
                        snackbar.showSnackbar("Backup copied to the clipboard.")
                    }
                }, modifier = Modifier.weight(1f)) { Text("Copy backup") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, Backup.export()).putExtra(Intent.EXTRA_SUBJECT, "Buddy backup.json")
                        ctx.startActivity(Intent.createChooser(send, "Share backup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }, modifier = Modifier.weight(1f)) { Text("Share backup") }
            }
            OutlinedButton(onClick = { importText = clipboard.getText()?.text.orEmpty(); importDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Import from clipboard") }
            }
        }

        item {
            SectionTitle("About")
            CardBox {
            Text("Buddy on the go 1.0.0", style = MaterialTheme.typography.bodyMedium)
            DisabledNote("Map data (when Online Mode is on) is from OpenStreetMap contributors. Privacy: Buddy has no account, no analytics and no ads. Nothing about your trips or alarms leaves your phone.")
            }
        }
    }

    if (importDialog) AlertDialog(
        onDismissRequest = { importDialog = false },
        title = { Text("Import backup") },
        text = { Text("Add everything from the backup on the clipboard (${importText.length} characters) to this phone?") },
        confirmButton = {
            TextButton(onClick = {
                importDialog = false
                scope.launch {
                    val msg = try { "Imported ${Backup.import(importText)} items." } catch (e: Exception) { "That is not a valid Buddy backup." }
                    Reschedule.all(ctx)
                    snackbar.showSnackbar(msg)
                }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = { importDialog = false }) { Text("Cancel") } },
    )
}

private data class Check(val title: String, val ok: Boolean, val fixLabel: String, val fix: () -> Unit)

@Composable
private fun ReliabilityCard(ctx: Context, onChanged: () -> Unit) {
    val nm = NotificationManagerCompat.from(ctx)
    val am = ctx.getSystemService(AlarmManager::class.java)
    val pm = ctx.getSystemService(PowerManager::class.java)
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
    fun go(i: Intent) { try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { ctx.startActivity(Intent(AS.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    val checks = listOf(
        Check("Notifications allowed", nm.areNotificationsEnabled(), "Fix") { go(Intent(AS.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AS.EXTRA_APP_PACKAGE, ctx.packageName)) },
        Check("Exact alarms allowed", Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms(), "Fix") { if (Build.VERSION.SDK_INT >= 31) go(Intent(AS.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))) },
        Check("Full-screen alerts allowed", Build.VERSION.SDK_INT < 34 || ctx.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent(), "Fix") { if (Build.VERSION.SDK_INT >= 34) go(Intent(AS.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))) },
        Check("Location permission", ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED, "Open") { go(Intent(AS.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) },
        Check("Location services on", lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER), "Open") { go(Intent(AS.ACTION_LOCATION_SOURCE_SETTINGS)) },
        Check("Battery optimization exempt", pm.isIgnoringBatteryOptimizations(ctx.packageName), "Fix") { go(Intent(AS.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) },
        Check("Phone battery saver off", !pm.isPowerSaveMode, "Open") { go(Intent(AS.ACTION_BATTERY_SAVER_SETTINGS)) },
    )
    CardBox {
        checks.forEach { c ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(if (c.ok) Color(0xFF2E9E6B) else Color(0xFFE5575B), CircleShape))
                Spacer(Modifier.width(12.dp))
                Text(c.title, Modifier.weight(1f))
                if (!c.ok) TextButton(onClick = { c.fix() }) { Text(c.fixLabel) }
            }
        }
        TextButton(onClick = onChanged) { Text("Re-check") }
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
    CardBox { Text("Phone guide (${Build.MANUFACTURER})", style = MaterialTheme.typography.titleSmall); Text(text, style = MaterialTheme.typography.bodyMedium) }
}
