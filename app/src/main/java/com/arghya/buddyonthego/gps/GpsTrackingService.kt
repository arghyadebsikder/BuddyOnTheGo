package com.arghya.buddyonthego.gps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.arghya.buddyonthego.AppScope
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.alarm.Notifs
import com.arghya.buddyonthego.alarm.RingKind
import com.arghya.buddyonthego.alarm.RingService
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.data.Route
import com.arghya.buddyonthego.domain.Fix
import com.arghya.buddyonthego.domain.Geo
import com.arghya.buddyonthego.domain.GpsAlarmEngine
import com.arghya.buddyonthego.domain.LatLng
import com.arghya.buddyonthego.domain.Polyline
import com.arghya.buddyonthego.domain.RouteEvent
import com.arghya.buddyonthego.domain.RouteGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * GPS-1 .. GPS-15 and the route rules. This class never imports NetworkGate or map code (NET-10, MAP-6).
 */
class GpsTrackingService : Service(), LocationListener {
    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var session: Session? = null
    private var engine: GpsAlarmEngine? = null
    private var guard: RouteGuard? = null
    private var destLat = Double.NaN
    private var destLng = Double.NaN
    private var radius = 500.0
    private var title = "Buddy is watching your trip"

    private val recorded = ArrayList<LatLng>()
    private var lastFixMs = 0L
    private var firstFixSeen = false
    private var alerted = false
    private var lostRung = false
    private var currentIntervalMs = 0L
    private var lastNotifMs = 0L
    private var lastNotifDist = -1.0
    private var startDistance = 0.0
    private var distance = Double.NaN
    private var etaSec = Double.NaN

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            TrackingStore.edit(this@GpsTrackingService) { putLong("heartbeat", now) }
            checkGpsLost(now)
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceLocator.init(this)
        if (intent?.action == ACTION_STOP) {
            finalizeAndStop()
            return START_NOT_STICKY
        }
        // Started with startForegroundService, so promote immediately (even if we stop right after).
        try {
            ServiceCompat.startForeground(
                this, Notifs.ID_TRIP, Notifs.trip(this, title, "Waiting for GPS...", indeterminate = true),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        val s = TrackingStore.get(this)
        if (s == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            session = s
            AppScope.io.launch {
                loadConfig(s)
                withContext(Dispatchers.Main) { begin(s) }
            }
        } else {
            TrackingStore.edit(this) { putLong("heartbeat", System.currentTimeMillis()) }
        }
        return START_STICKY // GPS-14: restored from TrackingStore after process death
    }

    private suspend fun loadConfig(s: Session) {
        val db = ServiceLocator.db
        val st = ServiceLocator.settings
        when (s.type) {
            TripController.TYPE_GPS -> {
                val a = db.gpsAlarmDao().get(s.refId) ?: return
                destLat = a.destLat; destLng = a.destLng; radius = a.radiusMeters.toDouble()
                title = "Buddy is watching your trip"
                engine = GpsAlarmEngine(a.destLat, a.destLng, radius, a.triggerType, a.leadMode, a.leadValue.toDouble())
            }
            TripController.TYPE_ROUTE -> {
                val r = db.routeDao().get(s.refId) ?: return
                val pts = Polyline.decode(r.encodedPolyline)
                if (pts.size < 2) return
                destLat = pts.last().lat; destLng = pts.last().lng; radius = r.destRadiusMeters.toDouble()
                guard = RouteGuard(
                    pts, r.corridorMeters.toDouble(), radius,
                    r.wrongDirectionAlarm && st.bool(K.WRONG_DIR, true),
                )
            }
            TripController.TYPE_RECORD -> title = "Recording route: ${s.name}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun begin(s: Session) {
        if (running) return
        if (engine == null && guard == null && s.type != TripController.TYPE_RECORD) {
            // The alarm or route was deleted. Nothing to watch.
            TripController.stop(this)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            RingService.start(this, RingKind.TRACKING_STOPPED, "Location permission missing", "Open Buddy and allow location, then start the trip again.")
            return
        }
        running = true
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startDistance = if (s.startDistance > 0) s.startDistance else 0.0
        alerted = s.alerted
        lastFixMs = max(s.lastFixMs, s.startedMs)

        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            RingService.start(this, RingKind.LOCATION_OFF, "Location is off", "Turn location on so Buddy can watch your trip.", vibe = 0)
        }

        request(if (s.type == TripController.TYPE_RECORD) 3000L else 5000L)

        // First backup estimate from the last known position, otherwise a long default.
        var backup = s.backupAt
        if (backup <= System.currentTimeMillis() && s.type != TripController.TYPE_RECORD) {
            val last = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: Exception) { null }
            backup = if (last != null && !destLat.isNaN() && System.currentTimeMillis() - last.time < 10 * 60_000L) {
                val d = Geo.haversine(last.latitude, last.longitude, destLat, destLng)
                System.currentTimeMillis() + (d / 2.0 * 1000).toLong().coerceAtMost(12 * 3600_000L) + BACKUP_BUFFER_MS
            } else System.currentTimeMillis() + 4 * 3600_000L
            TrackingStore.edit(this) { putLong("backupAt", backup) }
        }
        if (s.type != TripController.TYPE_RECORD) BackupAlarms.schedule(this, backup)
        Watchdog.schedule(this)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    @SuppressLint("MissingPermission")
    private fun request(intervalMs: Long) {
        try { lm.removeUpdates(this) } catch (_: Exception) { }
        currentIntervalMs = intervalMs
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0f, this, Looper.getMainLooper())
        } catch (e: Exception) {
            RingService.start(this, RingKind.LOCATION_OFF, "Location is off", "Buddy could not start GPS. Check location settings.")
        }
        TrackingStore.edit(this) { putLong("intervalMs", intervalMs) }
    }

    /** GPS-8 adaptive sampling. */
    private fun desiredInterval(): Long {
        val mode = ServiceLocator.settings.int(K.GPS_MODE, 1)
        if (guard == null && engine == null) return 3000L
        if (distance.isNaN()) return 5000L
        val secs = ((distance - radius) / (3 * 40.0)).coerceIn(2.0, 60.0)
        val mult = when (mode) { 0 -> 2.0; 2 -> 0.5; else -> 1.0 }
        return (secs * mult).coerceIn(1.0, 120.0).times(1000).toLong()
    }

    override fun onLocationChanged(loc: Location) {
        val now = System.currentTimeMillis()
        val quiet = ServiceLocator.settings.int(K.LOST_QUIET, 3) * 60_000L
        val gap = now - lastFixMs
        if (firstFixSeen && gap > quiet) {
            Notifs.status(this, Notifs.ID_QUIET, "GPS signal is back", "It was lost for about ${(gap / 60_000).coerceAtLeast(1)} min.", Notifs.CH_TRIP)
        }
        firstFixSeen = true
        lastFixMs = now
        lostRung = false

        val f = Fix(
            loc.latitude, loc.longitude,
            if (loc.hasAccuracy()) loc.accuracy else 50f,
            if (loc.hasSpeed()) loc.speed else 0f,
            now,
            if (loc.hasBearing()) loc.bearing else -1f,
        )
        val st = session ?: TrackingStore.get(this) ?: return

        when (st.type) {
            TripController.TYPE_GPS -> engine?.let { e ->
                val ring = e.onFix(f)
                distance = e.distance; etaSec = e.etaSec
                if (ring && !alerted) {
                    alerted = true
                    TrackingStore.edit(this) { putBoolean("alerted", true) }
                    ringArrival()
                }
            }
            TripController.TYPE_ROUTE -> guard?.let { g ->
                for (ev in g.onFix(f)) {
                    when (ev) {
                        RouteEvent.DESTINATION -> if (!alerted) {
                            alerted = true
                            TrackingStore.edit(this) { putBoolean("alerted", true) }
                            ringArrival()
                        }
                        RouteEvent.OFF_ROUTE -> RingService.start(
                            this, RingKind.OFF_ROUTE, "You may be off route",
                            "${fmt(g.offDistance)} from your route. Check where you are.",
                        )
                        RouteEvent.WRONG_DIRECTION -> RingService.start(
                            this, RingKind.WRONG_DIR, "Heading the wrong way",
                            "You are moving away from ${st.name}.",
                        )
                        RouteEvent.BACK_ON_ROUTE -> {
                            RingService.send(this, RingService.ACTION_DISMISS_KIND, RingKind.OFF_ROUTE)
                            Notifs.status(this, Notifs.ID_QUIET, "Back on route", "Buddy is watching your trip again.", Notifs.CH_TRIP)
                        }
                    }
                }
                distance = g.distanceToDest; etaSec = g.etaSec
            }
            TripController.TYPE_RECORD -> {
                val last = recorded.lastOrNull()
                if (last == null || Geo.haversine(last.lat, last.lng, f.lat, f.lng) >= 25.0) {
                    recorded.add(LatLng(f.lat, f.lng))
                }
            }
        }

        if (startDistance <= 0.0 && !distance.isNaN()) startDistance = distance
        TrackingStore.edit(this) {
            putLong("heartbeat", now); putLong("lastFixMs", now)
            TrackingStore.putDouble(this, "lastLat", f.lat); TrackingStore.putDouble(this, "lastLng", f.lng)
            if (!distance.isNaN()) TrackingStore.putDouble(this, "distance", distance)
            if (!etaSec.isNaN()) TrackingStore.putDouble(this, "etaSec", etaSec)
            putFloat("startDistance", startDistance.toFloat())
        }
        updateNotification(now, st)
        adaptSampling()
        reestimateBackup(now, st)
    }

    private fun ringArrival() {
        val st = session ?: return
        val name = st.name.ifBlank { "your destination" }
        RingService.start(
            this, RingKind.GPS_ARRIVAL, "Arriving: $name",
            "${fmt(distance)} away. Time to get up.", ownerId = st.refId,
        )
    }

    private fun adaptSampling() {
        if (!running) return
        val want = desiredInterval()
        if (currentIntervalMs == 0L || abs(want - currentIntervalMs) > currentIntervalMs * 0.3) request(want)
    }

    private fun reestimateBackup(now: Long, st: Session) {
        if (st.type == TripController.TYPE_RECORD || etaSec.isNaN() || alerted) return
        val eta = (etaSec.coerceAtMost(12 * 3600.0) * 1000).toLong()
        val target = now + eta + BACKUP_BUFFER_MS
        val cur = TrackingStore.get(this)?.backupAt ?: 0L
        if (abs(target - cur) > 5 * 60_000L) {
            TrackingStore.edit(this) { putLong("backupAt", target) }
            BackupAlarms.schedule(this, target)
        }
    }

    /** RNG-9 .. RNG-12: update when distance moved about 50 m and at least 12 s passed. */
    private fun updateNotification(now: Long, st: Session) {
        val first = lastNotifMs == 0L
        if (st.type == TripController.TYPE_RECORD) {
            if (now - lastNotifMs < 10_000 && !first) return
            lastNotifMs = now
            Notifs.post(this, Notifs.ID_TRIP, Notifs.trip(
                this, title, "${recorded.size} points, ${fmt(Polyline.lengthMeters(recorded))}", indeterminate = true))
            return
        }
        if (distance.isNaN()) return
        val moved = abs(distance - lastNotifDist) >= 50.0
        if (!first && !(now - lastNotifMs >= 12_000 && moved)) return
        lastNotifMs = now
        lastNotifDist = distance
        val total = max(startDistance, distance)
        val mins = if (etaSec.isNaN()) "" else ", about ${max(1, (etaSec / 60).roundToInt())} min"
        Notifs.post(this, Notifs.ID_TRIP, Notifs.trip(
            this, title, "${fmt(distance)} left$mins",
            progress = (total - distance).toInt(), max = total.toInt().coerceAtLeast(1)))
    }

    /** RTE-6: quiet warning when the fix returns, ring if it never does. */
    private fun checkGpsLost(now: Long) {
        val st = session ?: return
        if (st.type == TripController.TYPE_RECORD || alerted || lostRung) return
        val ringAfter = ServiceLocator.settings.int(K.LOST_RING, 15) * 60_000L
        if (now - lastFixMs > ringAfter) {
            lostRung = true
            val last = if (!distance.isNaN()) "Last known distance: ${fmt(distance)}." else "No position yet."
            RingService.start(this, RingKind.GPS_LOST, "Lost GPS signal", last)
        }
    }

    private fun fmt(m: Double): String {
        if (m.isNaN()) return "-"
        return if (ServiceLocator.settings.km) {
            if (m >= 1000) String.format(java.util.Locale.US, "%.1f km", m / 1000) else "${m.roundToInt()} m"
        } else {
            val mi = m / 1609.344
            if (mi >= 0.2) String.format(java.util.Locale.US, "%.1f mi", mi) else "${(m * 3.28084).roundToInt()} ft"
        }
    }

    // GPS-11: provider changes
    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER && running) {
            RingService.start(this, RingKind.LOCATION_OFF, "Location is off", "Turn location on so Buddy can watch your trip.")
        }
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            RingService.send(this, RingService.ACTION_DISMISS_KIND, RingKind.LOCATION_OFF)
        }
    }

    @Deprecated("Needed for API 26 to 28")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun cleanup() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { lm.removeUpdates(this) } catch (_: Exception) { }
    }

    private fun finalizeAndStop() {
        val wasRecord = session?.type == TripController.TYPE_RECORD
        val name = session?.name.orEmpty()
        val pts = recorded.toList()
        if (::lm.isInitialized) cleanup()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (wasRecord && pts.size >= 2) {
            AppScope.io.launch {
                val simple = Polyline.simplify(pts, 10.0)
                ServiceLocator.db.routeDao().upsert(
                    Route(name = name.ifBlank { "Recorded route" }, encodedPolyline = Polyline.encode(simple), createdFrom = "recorded"),
                )
                withContext(Dispatchers.Main) { stopSelf() }
            }
        } else {
            stopSelf()
        }
    }

    /** GPS-14: the backup alarm must outlive the task being swiped away. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        TrackingStore.get(this)?.let { s ->
            if (s.backupAt > System.currentTimeMillis()) BackupAlarms.schedule(this, s.backupAt)
            Watchdog.schedule(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (::lm.isInitialized) cleanup() else handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.arghya.buddyonthego.TRACK_START"
        const val ACTION_STOP = "com.arghya.buddyonthego.TRACK_STOP"
        const val BACKUP_BUFFER_MS = 10 * 60_000L
    }
}
