package com.arghya.buddyonthego.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.arghya.buddyonthego.data.Alarm
import com.arghya.buddyonthego.domain.NextTrigger
import com.arghya.buddyonthego.ui.MainActivity
import java.time.LocalDate
import java.time.ZoneId

/** Exact alarm helper used by backup, watchdog and reminder alarms. */
object Exact {
    fun set(ctx: Context, atMs: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val allowed = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (allowed) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
    }

    fun broadcast(ctx: Context, cls: Class<*>, action: String, data: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, cls).setAction(action).setData(Uri.parse(data)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** D9: time alarms use AlarmManager.setAlarmClock, never WorkManager. */
object AlarmScheduler {
    const val ACTION = "com.arghya.buddyonthego.ALARM"

    private fun pi(ctx: Context, id: Long, snooze: Boolean, count: Int = 0): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION)
            .setData(Uri.parse("buddy://alarm/$id" + if (snooze) "/s" else ""))
            .putExtra("id", id)
            .putExtra("snoozeCount", count)
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun showPi(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun nextTriggerFor(a: Alarm, now: Long = System.currentTimeMillis(), ignoreSkip: Boolean = false): Long? =
        NextTrigger.next(
            a.hour, a.minute, a.daysMask, now, ZoneId.systemDefault(),
            if (ignoreSkip) null else a.skipNextDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )

    fun schedule(ctx: Context, a: Alarm) {
        cancel(ctx, a.id)
        if (!a.enabled) return
        val t = nextTriggerFor(a) ?: return
        ctx.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(t, showPi(ctx)), pi(ctx, a.id, false))
    }

    fun scheduleSnooze(ctx: Context, id: Long, minutes: Int, newCount: Int) {
        val t = System.currentTimeMillis() + minutes * 60_000L
        ctx.getSystemService(AlarmManager::class.java)
            .setAlarmClock(AlarmManager.AlarmClockInfo(t, showPi(ctx)), pi(ctx, id, true, newCount))
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pi(ctx, id, false))
        am.cancel(pi(ctx, id, true))
    }
}
