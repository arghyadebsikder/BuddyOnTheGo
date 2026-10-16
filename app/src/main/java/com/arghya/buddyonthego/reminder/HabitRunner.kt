package com.arghya.buddyonthego.reminder

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.arghya.buddyonthego.R
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.alarm.Exact
import com.arghya.buddyonthego.alarm.Notifs
import com.arghya.buddyonthego.data.Habit
import com.arghya.buddyonthego.domain.HabitWindow
import com.arghya.buddyonthego.ui.Fmt
import java.time.ZoneId

/**
 * Habits and tasks with a start and a finish time. At the start time Buddy posts a "started" notification
 * (with a live countdown) and applies the chosen sound mode; at the finish time it puts the sound back.
 */
object HabitRunner {
    private const val PREFS = "habitrun"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activeNotifId(habitId: Long) = Notifs.ID_ACTIVE_BASE + (habitId % 5000).toInt()

    /** The window that is running right now, or null. */
    fun windowNow(h: Habit, now: Long = System.currentTimeMillis()): HabitWindow.Window? {
        if (h.archived || h.reminderMinutes < 0 || h.endMinutes < 0) return null
        return HabitWindow.activeAt(h.reminderMinutes, h.endMinutes, ReminderScheduler.maskOf(h), now, ZoneId.systemDefault())
    }

    private fun skippedWindow(ctx: Context, id: Long): Long = sp(ctx).getLong("skip_$id", -1L)

    /**
     * One place that makes the alarms and the sound match the habit: starts or ends the running session,
     * arms the finish alarm, and arms the next start. Safe to call any number of times.
     */
    fun sync(ctx: Context, h: Habit, now: Long = System.currentTimeMillis()) {
        ReminderScheduler.cancelHabit(ctx, h.id)
        if (h.archived || h.reminderMinutes < 0) {
            endSession(ctx, h.id)
            return
        }
        val zone = ZoneId.systemDefault()
        val mask = ReminderScheduler.maskOf(h)
        val w = windowNow(h, now)
        if (w != null && skippedWindow(ctx, h.id) != w.startMs) {
            beginSession(ctx, h, w)
            Exact.set(ctx, w.endMs, ReminderScheduler.endPi(ctx, h.id))
        } else {
            endSession(ctx, h.id)
        }
        HabitWindow.nextStart(h.reminderMinutes, mask, now, zone)?.let { Exact.set(ctx, it, ReminderScheduler.startPi(ctx, h.id)) }
    }

    private fun beginSession(ctx: Context, h: Habit, w: HabitWindow.Window) {
        SoundGuard.begin(ctx, h.id, h.soundMode)
        val h12 = ServiceLocator.settings.h12
        val end = java.util.Calendar.getInstance().apply { timeInMillis = w.endMs }
        val doneIntent = Intent(ctx, ReminderReceiver::class.java)
            .setAction(ReminderScheduler.ACTION_HABIT_DONE)
            .setData(Uri.parse("buddy://habitdone/${h.id}"))
        val donePi = PendingIntent.getBroadcast(ctx, 0, doneIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val mode = SoundMode.names.getOrElse(h.soundMode) { "General" }
        val n = NotificationCompat.Builder(ctx, Notifs.CH_ACTIVE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Started: ${h.name}")
            .setContentText("Until ${Fmt.time(end.get(java.util.Calendar.HOUR_OF_DAY), end.get(java.util.Calendar.MINUTE), h12)}  -  sound: $mode")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(w.endMs)
            .setShowWhen(true)
            .setProgress(1000, (w.progress(System.currentTimeMillis()) * 1000).toInt(), false)
            .setContentIntent(Notifs.openApp(ctx))
            .addAction(0, "Done", donePi)
            .build()
        Notifs.post(ctx, activeNotifId(h.id), n)
    }

    private fun endSession(ctx: Context, habitId: Long) {
        SoundGuard.end(ctx, habitId)
        Notifs.cancel(ctx, activeNotifId(habitId))
    }

    /** Removes everything this habit has running (used when it is deleted or its feature is switched off). */
    fun stop(ctx: Context, habitId: Long) {
        ReminderScheduler.cancelHabit(ctx, habitId)
        endSession(ctx, habitId)
    }

    /** The finish time has arrived. */
    fun finishWindow(ctx: Context, h: Habit) {
        sp(ctx).edit().remove("skip_${h.id}").apply()
        endSession(ctx, h.id)
    }

    /** The person marked it done before the finish time: end the session now, but keep tomorrow's schedule. */
    fun finishEarly(ctx: Context, h: Habit) {
        val w = windowNow(h)
        if (w != null) sp(ctx).edit().putLong("skip_${h.id}", w.startMs).apply()
        sync(ctx, h)
    }

    /** Switch the sound mode of a running (or future) task. The new mode is applied at once if it is running. */
    fun applySoundMode(ctx: Context, h: Habit) = sync(ctx, h)
}
