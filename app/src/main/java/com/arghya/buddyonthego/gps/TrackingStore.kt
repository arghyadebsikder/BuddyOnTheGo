package com.arghya.buddyonthego.gps

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import com.arghya.buddyonthego.alarm.Exact
import com.arghya.buddyonthego.alarm.Notifs
import kotlinx.coroutines.flow.MutableStateFlow

data class Session(
    val type: Int,
    val refId: Long,
    val name: String,
    val startedMs: Long,
    val startDistance: Double,
    val heartbeat: Long,
    val lastFixMs: Long,
    val lastLat: Double,
    val lastLng: Double,
    val distance: Double,
    val etaSec: Double,
    val backupAt: Long,
    val alerted: Boolean,
    val intervalMs: Long,
)

/** Runtime state (section 7.2). Kept in SharedPreferences so receivers can read it synchronously. */
object TrackingStore {
    private fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences("tracking", Context.MODE_PRIVATE)

    /** The UI watches this. */
    val live = MutableStateFlow<Session?>(null)

    fun get(ctx: Context): Session? {
        val p = sp(ctx)
        if (!p.getBoolean("active", false)) return null
        return Session(
            type = p.getInt("type", 0), refId = p.getLong("refId", 0), name = p.getString("name", "") ?: "",
            startedMs = p.getLong("startedMs", 0), startDistance = p.getFloat("startDistance", 0f).toDouble(),
            heartbeat = p.getLong("heartbeat", 0), lastFixMs = p.getLong("lastFixMs", 0),
            lastLat = Double.fromBits(p.getLong("lastLat", Double.NaN.toBits())),
            lastLng = Double.fromBits(p.getLong("lastLng", Double.NaN.toBits())),
            distance = Double.fromBits(p.getLong("distance", Double.NaN.toBits())),
            etaSec = Double.fromBits(p.getLong("etaSec", Double.NaN.toBits())),
            backupAt = p.getLong("backupAt", 0), alerted = p.getBoolean("alerted", false),
            intervalMs = p.getLong("intervalMs", 5000),
        )
    }

    fun begin(ctx: Context, type: Int, refId: Long, name: String) {
        sp(ctx).edit().clear()
            .putBoolean("active", true).putInt("type", type).putLong("refId", refId).putString("name", name)
            .putLong("startedMs", System.currentTimeMillis()).putLong("heartbeat", System.currentTimeMillis())
            .apply()
        live.value = get(ctx)
    }

    fun edit(ctx: Context, block: SharedPreferences.Editor.() -> Unit) {
        val p = sp(ctx)
        if (!p.getBoolean("active", false)) return
        p.edit().apply(block).apply()
        live.value = get(ctx)
    }

    fun putDouble(e: SharedPreferences.Editor, key: String, v: Double) { e.putLong(key, v.toBits()) }

    fun clear(ctx: Context) {
        sp(ctx).edit().clear().apply()
        live.value = null
    }
}

object BackupAlarms {
    private fun pi(ctx: Context) = Exact.broadcast(ctx, BackupAlarmReceiver::class.java, "com.arghya.buddyonthego.BACKUP", "buddy://backup")
    fun schedule(ctx: Context, atMs: Long) {
        if (!com.arghya.buddyonthego.ServiceLocator.settings.bool(com.arghya.buddyonthego.data.K.BACKUP_TIMER, true)) return
        Exact.set(ctx, atMs, pi(ctx))
    }
    fun cancel(ctx: Context) = ctx.getSystemService(android.app.AlarmManager::class.java).cancel(pi(ctx))
}

object Watchdog {
    const val PERIOD_MS = 120_000L
    private fun pi(ctx: Context) = Exact.broadcast(ctx, WatchdogReceiver::class.java, "com.arghya.buddyonthego.WATCHDOG", "buddy://watchdog")
    fun schedule(ctx: Context, atMs: Long = System.currentTimeMillis() + PERIOD_MS) = Exact.set(ctx, atMs, pi(ctx))
    fun cancel(ctx: Context) = ctx.getSystemService(android.app.AlarmManager::class.java).cancel(pi(ctx))
}

/** Starts and stops trips. GPS-1 / D7: start only while the app is visible. */
object TripController {
    const val TYPE_GPS = 1
    const val TYPE_ROUTE = 2
    const val TYPE_RECORD = 3

    fun start(ctx: Context, type: Int, refId: Long, name: String) {
        TrackingStore.begin(ctx, type, refId, name)
        ContextCompat.startForegroundService(
            ctx, Intent(ctx, GpsTrackingService::class.java).setAction(GpsTrackingService.ACTION_START),
        )
    }

    /** D8: after reboot the user taps "Resume trip?", which opens the app, which calls this while visible. */
    fun resume(ctx: Context) {
        if (TrackingStore.get(ctx) == null) return
        Notifs.cancel(ctx, Notifs.ID_RESUME)
        ContextCompat.startForegroundService(
            ctx, Intent(ctx, GpsTrackingService::class.java).setAction(GpsTrackingService.ACTION_START),
        )
    }

    /** GPS-13: clean stop. */
    fun stop(ctx: Context) {
        BackupAlarms.cancel(ctx)
        Watchdog.cancel(ctx)
        Notifs.cancel(ctx, Notifs.ID_TRIP)
        Notifs.cancel(ctx, Notifs.ID_RESUME)
        try {
            ctx.startService(Intent(ctx, GpsTrackingService::class.java).setAction(GpsTrackingService.ACTION_STOP))
        } catch (_: Exception) {
            ctx.stopService(Intent(ctx, GpsTrackingService::class.java))
        }
        TrackingStore.clear(ctx)
    }
}
