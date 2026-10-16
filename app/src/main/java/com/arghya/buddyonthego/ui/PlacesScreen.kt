package com.arghya.buddyonthego.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.util.Xml
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.data.GpsAlarm
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.data.Route
import com.arghya.buddyonthego.data.SavedPlace
import com.arghya.buddyonthego.domain.CoordParser
import com.arghya.buddyonthego.domain.LatLng
import com.arghya.buddyonthego.domain.Polyline
import com.arghya.buddyonthego.gps.TrackingStore
import com.arghya.buddyonthego.gps.TripController
import com.arghya.buddyonthego.net.NetResult
import com.arghya.buddyonthego.net.PlaceHit
import com.arghya.buddyonthego.net.PlaceSearch
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

fun hasLocationPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Suppress("MissingPermission")
fun lastKnownLocation(ctx: Context): Location? {
    if (!hasLocationPermission(ctx)) return null
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
        .maxByOrNull { it.time }
}

fun parseGpxOrKml(ctx: Context, uri: Uri): List<LatLng> {
    val out = ArrayList<LatLng>()
    ctx.contentResolver.openInputStream(uri)?.use { stream ->
        val p = Xml.newPullParser()
        p.setInput(stream, null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "trkpt", "rtept" -> {
                        val lat = p.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lon = p.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) out.add(LatLng(lat, lon))
                    }
                    "coordinates" -> { // KML: lng,lat,alt separated by whitespace
                        p.nextText().trim().split(Regex("\\s+")).forEach { t ->
                            val parts = t.split(",")
                            val lng = parts.getOrNull(0)?.toDoubleOrNull()
                            val lat = parts.getOrNull(1)?.toDoubleOrNull()
                            if (lat != null && lng != null) out.add(LatLng(lat, lng))
                        }
                    }
                }
            }
            ev = p.next()
        }
    }
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlacesScreen(snackbar: SnackbarHostState, sharedText: String?, onSharedConsumed: () -> Unit) {
    val ctx = LocalContext.current
    val db = ServiceLocator.db
    val scope = rememberCoroutineScope()
    val gpsAlarms by db.gpsAlarmDao().observeAll().collectAsState(initial = emptyList())
    val routes by db.routeDao().observeAll().collectAsState(initial = emptyList())
    val places by db.savedPlaceDao().observeAll().collectAsState(initial = emptyList())
    val session by TrackingStore.live.collectAsState()
    val km = rememberBool(K.UNITS_KM, true)

    var editingGps by remember { mutableStateOf<GpsAlarm?>(null) }
    var editingRoute by remember { mutableStateOf<Route?>(null) }
    var recordDialog by remember { mutableStateOf(false) }
    var batteryDialog by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<Triple<Int, Long, String>?>(null) }
    var initialShared by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            initialShared = sharedText
            editingGps = GpsAlarm()
            onSharedConsumed()
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun doStart(t: Triple<Int, Long, String>) {
        TripController.start(ctx, t.first, t.second, t.third)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName) && !ServiceLocator.settings.bool(K.ASKED_BATTERY, false)) {
            ServiceLocator.settings.put(K.ASKED_BATTERY, true)
            batteryDialog = true
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val p = pendingStart
        pendingStart = null
        if (p != null && res[Manifest.permission.ACCESS_FINE_LOCATION] == true) doStart(p)
        else scope.launch { snackbar.showSnackbar("Location permission is needed to watch a trip.") }
    }

    fun requestStart(type: Int, id: Long, name: String) {
        if (TrackingStore.live.value != null || TrackingStore.get(ctx) != null) {
            scope.launch { snackbar.showSnackbar("Stop the current trip first.") }
            return
        }
        val t = Triple(type, id, name)
        if (hasLocationPermission(ctx)) doStart(t) else {
            pendingStart = t
            permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val pts = try { parseGpxOrKml(ctx, uri) } catch (e: Exception) { emptyList() }
            if (pts.size < 2) snackbar.showSnackbar("No route found in that file.")
            else {
                val simple = Polyline.simplify(pts, 10.0)
                db.routeDao().upsert(Route(name = "Imported route", encodedPolyline = Polyline.encode(simple), createdFrom = "imported"))
                snackbar.showSnackbar("Route imported (${simple.size} points).")
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "header") { ScreenHeader("Places and trips", "Wake on place") }

        session?.let { s ->
            item(key = "trip") {
                CardBox(accent = true) {
                    Text(if (s.type == TripController.TYPE_RECORD) "Recording route" else "Trip running", style = MaterialTheme.typography.titleMedium)
                    Text(s.name.ifBlank { "Trip" })
                    if (!s.distance.isNaN()) Text("${Fmt.dist(s.distance, km)} left")
                    Spacer(Modifier.height(8.dp))
                    var confirm by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { confirm = true }) { Text("Stop tracking") }
                    if (confirm) AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Stop tracking?") },
                        text = { Text("Buddy will stop watching this trip.") },
                        confirmButton = { TextButton(onClick = { confirm = false; TripController.stop(ctx) }) { Text("Stop") } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep going") } },
                    )
                }
            }
        }

        item { SectionTitle("GPS alarms") }
        item {
            Button(onClick = { initialShared = null; editingGps = GpsAlarm() }, modifier = Modifier.fillMaxWidth()) { Text("New GPS alarm") }
        }
        if (gpsAlarms.isEmpty()) item { DisabledNote("Wake me when I arrive somewhere. Add a destination and tap Start while the app is open.") }
        items(gpsAlarms, key = { "g${it.id}" }) { a ->
            CardBox {
                Text(a.name.ifBlank { "Destination" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${listOf("Arrive", "Leave", "Pass")[a.triggerType]} within ${Fmt.dist(a.radiusMeters.toDouble(), km)}" +
                        if (a.leadMode == 1 && a.leadValue > 0) ", ${a.leadValue} min before" else if (a.leadValue > 0) ", lead ${Fmt.dist(a.leadValue.toDouble(), km)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { requestStart(TripController.TYPE_GPS, a.id, a.name.ifBlank { "Destination" }) }) { Text("Start") }
                    OutlinedButton(onClick = { initialShared = null; editingGps = a }) { Text("Edit") }
                }
            }
        }

        item { SectionTitle("Routes (route guard)") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { recordDialog = true }, modifier = Modifier.weight(1f)) { Text("Record") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("Import GPX/KML") }
            }
        }
        if (routes.isEmpty()) item { DisabledNote("Record a trip once, then Buddy watches it for off-route, wrong direction and arrival.") }
        items(routes, key = { "r${it.id}" }) { r ->
            val len = remember(r.encodedPolyline) { Polyline.lengthMeters(Polyline.decode(r.encodedPolyline)) }
            CardBox {
                Text(r.name.ifBlank { "Route" }, style = MaterialTheme.typography.titleMedium)
                Text("${Fmt.dist(len, km)}, corridor ${r.corridorMeters} m, from ${r.createdFrom}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { requestStart(TripController.TYPE_ROUTE, r.id, r.name.ifBlank { "Route" }) }) { Text("Start") }
                    OutlinedButton(onClick = { editingRoute = r }) { Text("Edit") }
                }
            }
        }

        item { SectionTitle("Saved places") }
        if (places.isEmpty()) item { DisabledNote("Save a destination from the GPS alarm editor to reuse it here.") }
        items(places, key = { "p${it.id}" }) { p ->
            CardBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { scope.launch { db.gpsAlarmDao().upsert(GpsAlarm(name = p.name, destLat = p.lat, destLng = p.lng, radiusMeters = p.radiusMeters)) } }) { Text("Make alarm") }
                    TextButton(onClick = { scope.launch { db.savedPlaceDao().delete(p.id) } }) { Text("Delete") }
                }
            }
        }
        item { DisabledNote("Map picker and offline map regions are not in this build. Use pasted coordinates or links, search (Online Mode), or Use my location.") }
    }

    editingGps?.let { a ->
        GpsAlarmEditor(
            initial = a, initialText = initialShared, places = places, snackbar = snackbar,
            onDismiss = { editingGps = null; initialShared = null },
            onSave = { saved ->
                editingGps = null; initialShared = null
                scope.launch { db.gpsAlarmDao().upsert(saved) }
            },
            onDelete = if (a.id != 0L) ({ editingGps = null; scope.launch { db.gpsAlarmDao().delete(a.id) } }) else null,
            onSavePlace = { name, lat, lng, radius -> scope.launch { db.savedPlaceDao().upsert(SavedPlace(name = name, lat = lat, lng = lng, radiusMeters = radius)) } },
        )
    }

    editingRoute?.let { r ->
        var name by remember(r.id) { mutableStateOf(r.name) }
        var corridor by remember(r.id) { mutableIntStateOf(r.corridorMeters) }
        var radius by remember(r.id) { mutableIntStateOf(r.destRadiusMeters) }
        var wrong by remember(r.id) { mutableStateOf(r.wrongDirectionAlarm) }
        AlertDialog(
            onDismissRequest = { editingRoute = null },
            title = { Text("Edit route") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    Stepper("Corridor width", corridor, 100, 1000, 50, " m") { corridor = it }
                    Stepper("Destination radius", radius, 100, 2000, 50, " m") { radius = it }
                    SwitchRow("Wrong-direction alarm", checked = wrong) { wrong = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingRoute = null
                    scope.launch { db.routeDao().upsert(r.copy(name = name.trim(), corridorMeters = corridor, destRadiusMeters = radius, wrongDirectionAlarm = wrong)) }
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { editingRoute = null; scope.launch { db.routeDao().delete(r.id) } }) { Text("Delete") }
                    TextButton(onClick = { editingRoute = null }) { Text("Cancel") }
                }
            },
        )
    }

    if (recordDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { recordDialog = false },
            title = { Text("Record a route") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ride or walk the route once with this app open, then tap Stop tracking.")
                    OutlinedTextField(name, { name = it }, label = { Text("Route name") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { recordDialog = false; requestStart(TripController.TYPE_RECORD, 0, name.ifBlank { "Recorded route" }) }) { Text("Start recording") }
            },
            dismissButton = { TextButton(onClick = { recordDialog = false }) { Text("Cancel") } },
        )
    }

    if (batteryDialog) AlertDialog(
        onDismissRequest = { batteryDialog = false },
        title = { Text("Keep Buddy awake") },
        text = { Text("So Android does not stop Buddy while you sleep, allow it to ignore battery optimization.") },
        confirmButton = {
            TextButton(onClick = {
                batteryDialog = false
                val direct = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                try { ctx.startActivity(direct) } catch (_: Exception) {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }) { Text("Allow") }
        },
        dismissButton = { TextButton(onClick = { batteryDialog = false }) { Text("Not now") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GpsAlarmEditor(
    initial: GpsAlarm, initialText: String?, places: List<SavedPlace>, snackbar: SnackbarHostState,
    onDismiss: () -> Unit, onSave: (GpsAlarm) -> Unit, onDelete: (() -> Unit)?,
    onSavePlace: (String, Double, Double, Int) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val online = rememberBool(K.ONLINE, false)

    var name by remember { mutableStateOf(initial.name) }
    var dest by remember { mutableStateOf(if (initial.id != 0L || initial.destLat != 0.0) LatLng(initial.destLat, initial.destLng) else null) }
    var destText by remember { mutableStateOf(initialText ?: if (dest != null) String.format(Locale.US, "%.6f, %.6f", dest!!.lat, dest!!.lng) else "") }
    var radius by remember { mutableIntStateOf(initial.radiusMeters) }
    var trigger by remember { mutableIntStateOf(initial.triggerType) }
    var leadMode by remember { mutableIntStateOf(initial.leadMode) }
    var leadValue by remember { mutableIntStateOf(initial.leadValue) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PlaceHit>>(emptyList()) }
    var searchMsg by remember { mutableStateOf<String?>(null) }

    fun applyText(t: String) {
        destText = t
        CoordParser.parse(t)?.let { dest = it }
    }

    LaunchedEffect(initialText) {
        if (initialText != null) {
            if (CoordParser.isShortLink(initialText)) {
                when (val r = ServiceLocator.gate.resolveShortLink(initialText.trim().split(Regex("\\s+")).first { it.startsWith("http") })) {
                    is NetResult.Ok -> applyText(r.finalUrl)
                    is NetResult.Fail -> searchMsg = "Short links need Online Mode. ${r.reason.message} Paste the full link or coordinates instead."
                }
            } else applyText(initialText)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (initial.id == 0L) "New GPS alarm" else "Edit GPS alarm", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(name, { name = it }, label = { Text("Name (for example Central Station)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                Text("Destination", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    destText, { applyText(it) }, label = { Text("Coordinates or map link") },
                    supportingText = { Text(dest?.let { String.format(Locale.US, "Using %.5f, %.5f", it.lat, it.lng) } ?: "Paste 23.8103, 90.4125 or a full Google Maps / OpenStreetMap link") },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.getText()?.text?.let { applyText(it) } }) { Text("Paste") }
                    OutlinedButton(onClick = {
                        val l = lastKnownLocation(ctx)
                        if (l == null) scope.launch { snackbar.showSnackbar("No location yet. Allow location and try again outdoors.") }
                        else { dest = LatLng(l.latitude, l.longitude); destText = String.format(Locale.US, "%.6f, %.6f", l.latitude, l.longitude) }
                    }) { Text("Use my location") }
                }
                if (places.isNotEmpty()) {
                    Text("Saved places", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        places.forEach { p ->
                            AssistChip(onClick = {
                                dest = LatLng(p.lat, p.lng); destText = String.format(Locale.US, "%.6f, %.6f", p.lat, p.lng)
                                if (name.isBlank()) name = p.name
                                radius = p.radiusMeters
                            }, label = { Text(p.name) })
                        }
                    }
                }

                if (online) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(query, { query = it }, label = { Text("Search a place (online)") }, singleLine = true, modifier = Modifier.weight(1f))
                        Button(onClick = {
                            scope.launch {
                                val near = lastKnownLocation(ctx)
                                val out = PlaceSearch(ServiceLocator.gate).search(query, near?.latitude, near?.longitude)
                                hits = out.hits
                                searchMsg = out.error ?: if (out.hits.isEmpty()) "No results." else null
                            }
                        }) { Text("Search") }
                    }
                    hits.forEach { h ->
                        TextButton(onClick = {
                            dest = LatLng(h.lat, h.lng); destText = String.format(Locale.US, "%.6f, %.6f", h.lat, h.lng)
                            if (name.isBlank()) name = h.name
                            hits = emptyList()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(h.name)
                                Text("${h.detail}  (${h.source})", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    DisabledNote("Place search needs Online Mode (Settings, Connectivity).")
                }
                searchMsg?.let { DisabledNote(it) }

                Stepper("Radius", radius, 50, 5000, 50, " m") { radius = it }
                Text("Trigger", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Arrive", "Leave", "Pass").forEachIndexed { i, n -> FilterChip(selected = trigger == i, onClick = { trigger = i }, label = { Text(n) }) }
                }
                if (trigger == 0) {
                    Text("Ring early by", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = leadMode == 0, onClick = { leadMode = 0 }, label = { Text("Distance") })
                        FilterChip(selected = leadMode == 1, onClick = { leadMode = 1 }, label = { Text("Minutes before arrival") })
                    }
                    if (leadMode == 0) Stepper("Lead distance", leadValue, 0, 5000, 100, " m") { leadValue = it }
                    else Stepper("Minutes before", leadValue, 0, 60, 1, " min") { leadValue = it }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(enabled = dest != null, onClick = {
                        val d = dest ?: return@Button
                        onSave(initial.copy(name = name.trim(), destLat = d.lat, destLng = d.lng, radiusMeters = radius, triggerType = trigger, leadMode = leadMode, leadValue = leadValue))
                    }, modifier = Modifier.weight(1f)) { Text("Save") }
                }
                if (dest != null && name.isNotBlank()) {
                    TextButton(onClick = { onSavePlace(name.trim(), dest!!.lat, dest!!.lng, radius); scope.launch { snackbar.showSnackbar("Saved to places.") } }, modifier = Modifier.fillMaxWidth()) { Text("Also save as a place") }
                }
                if (onDelete != null) TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete alarm") }
            }
        }
    }
}
