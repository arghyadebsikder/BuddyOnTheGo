package com.arghya.buddyonthego.gps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.alarm.RingKind
import com.arghya.buddyonthego.alarm.RingService
import com.arghya.buddyonthego.data.K

/** GPS-9: rings "Check your location" if the tracking service died and nobody ended the trip. */
class BackupAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ServiceLocator.init(ctx)
        if (TrackingStore.get(ctx) == null) return
        RingService.start(
            ctx, RingKind.BACKUP, "Check your location",
            "Tracking stopped, so Buddy is waking you to be safe.",
        )
    }
}

/** GPS-10: heartbeat watchdog. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ServiceLocator.init(ctx)
        val s = TrackingStore.get(ctx) ?: return
        val now = System.currentTimeMillis()
        val staleAfter = maxOf(90_000L, s.intervalMs * 3)
        if (now - s.heartbeat > staleAfter) {
            try {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, GpsTrackingService::class.java).setAction(GpsTrackingService.ACTION_START),
                )
            } catch (e: Exception) {
                RingService.start(ctx, RingKind.TRACKING_STOPPED, "Tracking stopped", "Buddy lost track of your trip. Check where you are.")
                return
            }
            // A restarted service writes a fresh heartbeat. If it is still stale at the next check we ring.
            if (s.alerted.not() && now - s.heartbeat > staleAfter * 3) {
                RingService.start(ctx, RingKind.TRACKING_STOPPED, "Tracking stopped", "Buddy lost track of your trip. Check where you are.")
            }
        }
        if (ServiceLocator.settings.bool(K.BACKUP_TIMER, true)) Watchdog.schedule(ctx)
    }
}
