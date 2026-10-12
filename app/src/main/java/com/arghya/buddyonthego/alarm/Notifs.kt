package com.arghya.buddyonthego.alarm

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.arghya.buddyonthego.R
import com.arghya.buddyonthego.gps.GpsTrackingService
import com.arghya.buddyonthego.ui.MainActivity

object RingKind {
    const val TIME = 0
    const val GPS_ARRIVAL = 1
    const val OFF_ROUTE = 2
    const val WRONG_DIR = 3
    const val GPS_LOST = 4
    const val BACKUP = 5
    const val TRACKING_STOPPED = 6
    const val LOCATION_OFF = 7

    fun isJourneyProblem(k: Int) = k == OFF_ROUTE || k == WRONG_DIR || k == GPS_LOST || k == LOCATION_OFF
    /** Kinds whose dismissal ends the whole trip. */
    fun endsTrip(k: Int) = k == GPS_ARRIVAL || k == BACKUP || k == TRACKING_STOPPED
}

object Notifs {
    const val CH_RING = "ring"
    const val CH_JOURNEY = "journey"
    const val CH_TRIP = "trip"
    const val CH_REMIND = "reminders"
    const val CH_STATUS = "status"

    const val ID_RING = 1001
    const val ID_TRIP = 1002
    const val ID_RESUME = 1003
    const val ID_MISSED = 1004
    const val ID_QUIET = 1005
    const val ID_REMIND_BASE = 20000

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: String, imp: Int, desc: String, silentRing: Boolean = false) {
            val c = NotificationChannel(id, name, imp).apply {
                description = desc
                if (silentRing) { // sound and vibration are played by RingService itself (RNG-2)
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            }
            nm.createNotificationChannel(c)
        }
        ch(CH_RING, "Alarm ringing", NotificationManager.IMPORTANCE_HIGH, "Time alarms and arrival alarms", true)
        ch(CH_JOURNEY, "Journey alert", NotificationManager.IMPORTANCE_HIGH, "Off route, wrong direction, GPS lost", true)
        ch(CH_TRIP, "Trip in progress", NotificationManager.IMPORTANCE_LOW, "Ongoing countdown and progress")
        ch(CH_REMIND, "Reminders", NotificationManager.IMPORTANCE_DEFAULT, "Habit and task reminders")
        ch(CH_STATUS, "App status", NotificationManager.IMPORTANCE_LOW, "Warnings and status")
    }

    private fun mutable(flags: Int = 0) = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or flags

    fun openApp(ctx: Context, extra: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (extra != null) i.putExtra(extra, true)
        i.data = Uri.parse("buddy://open/${extra ?: "home"}")
        return PendingIntent.getActivity(ctx, 0, i, mutable())
    }

    fun ring(ctx: Context, info: RingInfo): Notification {
        val ringPi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            mutable(),
        )
        fun svc(action: String, code: Int) = PendingIntent.getService(
            ctx, code, Intent(ctx, RingService::class.java).setAction(action), mutable(),
        )
        val journey = RingKind.isJourneyProblem(info.kind)
        val b = NotificationCompat.Builder(ctx, if (journey) CH_JOURNEY else CH_RING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(info.title)
            .setContentText(info.body)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(ringPi, true)
            .setContentIntent(ringPi)
        val dismissLabel = if (RingKind.endsTrip(info.kind)) "Stop tracking" else "Dismiss"
        b.addAction(0, dismissLabel, svc(RingService.ACTION_DISMISS, 1))
        if (info.kind == RingKind.TIME && info.snoozeCount < info.maxSnoozes) {
            b.addAction(0, "Snooze", svc(RingService.ACTION_SNOOZE, 2))
        }
        return b.build()
    }

    fun trip(
        ctx: Context, title: String, text: String,
        progress: Int = 0, max: Int = 0, indeterminate: Boolean = false,
    ): Notification {
        val pub = NotificationCompat.Builder(ctx, CH_TRIP)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Buddy trip active")
            .setContentText("Details hidden on lockscreen")
            .build()
        val stop = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_CONFIRM_STOP, true)
                .setData(Uri.parse("buddy://open/stop")),
            mutable(),
        )
        return NotificationCompat.Builder(ctx, CH_TRIP)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(pub)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Open map", openApp(ctx))
            .addAction(0, "Stop tracking", stop)
            .apply { if (max > 0 || indeterminate) setProgress(max, progress.coerceIn(0, max), indeterminate) }
            .build()
    }

    @SuppressLint("MissingPermission")
    fun post(ctx: Context, id: Int, n: Notification) {
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) { }
    }

    fun cancel(ctx: Context, id: Int) = NotificationManagerCompat.from(ctx).cancel(id)

    fun status(ctx: Context, id: Int, title: String, text: String, channel: String = CH_STATUS) {
        post(
            ctx, id,
            NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build(),
        )
    }

    fun resumePrompt(ctx: Context) {
        post(
            ctx, ID_RESUME,
            NotificationCompat.Builder(ctx, CH_JOURNEY)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Resume trip?")
                .setContentText("The phone restarted. Tap to continue watching your trip.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx, MainActivity.EXTRA_RESUME))
                .build(),
        )
    }
}
