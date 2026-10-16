package com.arghya.buddyonthego.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.arghya.buddyonthego.AppScope
import com.arghya.buddyonthego.R
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.alarm.AlarmScheduler
import com.arghya.buddyonthego.alarm.Exact
import com.arghya.buddyonthego.alarm.Notifs
import com.arghya.buddyonthego.data.Habit
import com.arghya.buddyonthego.data.HabitLog
import com.arghya.buddyonthego.data.Task
import com.arghya.buddyonthego.domain.NextTrigger
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** HAB-5 and TSK-6: reminders go through AlarmManager into the Reminders channel. */
object ReminderScheduler {
    /** Start time of a habit. */
    const val ACTION_HABIT = "com.arghya.buddyonthego.REMIND_HABIT"
    /** Finish time of a habit. */
    const val ACTION_HABIT_END = "com.arghya.buddyonthego.HABIT_END"
    const val ACTION_TASK = "com.arghya.buddyonthego.REMIND_TASK"
    const val ACTION_HABIT_DONE = "com.arghya.buddyonthego.HABIT_DONE"

    fun startPi(ctx: Context, id: Long) = Exact.broadcast(ctx, ReminderReceiver::class.java, ACTION_HABIT, "buddy://habit/$id")

    fun endPi(ctx: Context, id: Long) = Exact.broadcast(ctx, ReminderReceiver::class.java, ACTION_HABIT_END, "buddy://habitend/$id")

    private fun taskPi(ctx: Context, id: Long) = Exact.broadcast(ctx, ReminderReceiver::class.java, ACTION_TASK, "buddy://task/$id")

    /** Days a habit runs on. Bit 0 = Monday. Only "chosen days" habits use their own mask. */
    fun maskOf(h: Habit): Int = if (h.scheduleType == 1 && h.daysMask != 0) h.daysMask else 127

    /** Arms the start alarm (and the finish alarm while a timed habit is running). */
    fun scheduleHabit(ctx: Context, h: Habit) = HabitRunner.sync(ctx, h)

    /** Cancels only the pending alarms. Use HabitRunner.stop to also end a running session. */
    fun cancelHabit(ctx: Context, id: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(startPi(ctx, id))
        am.cancel(endPi(ctx, id))
    }

    fun scheduleTask(ctx: Context, t: Task) {
        cancelTask(ctx, t.id)
        val due = t.dueAt ?: return
        if (!t.reminder || t.done || due <= System.currentTimeMillis()) return
        Exact.set(ctx, due, taskPi(ctx, t.id))
    }

    fun cancelTask(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(taskPi(ctx, id))
    }
}

/** FM-1 / FM-2: one place that cancels or restores everything according to module flags. */
object Reschedule {
    suspend fun all(ctx: Context) {
        val db = ServiceLocator.db
        val s = ServiceLocator.settings
        for (a in db.alarmDao().getAll()) {
            if (s.modAlarm) AlarmScheduler.schedule(ctx, a) else AlarmScheduler.cancel(ctx, a.id)
        }
        for (h in db.habitDao().allHabits()) {
            if (s.modHabit) ReminderScheduler.scheduleHabit(ctx, h) else HabitRunner.stop(ctx, h.id)
        }
        for (t in db.taskDao().getAll()) {
            if (s.modTodo) ReminderScheduler.scheduleTask(ctx, t) else ReminderScheduler.cancelTask(ctx, t.id)
        }
        if (!s.modGps) com.arghya.buddyonthego.gps.TripController.stop(ctx)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ServiceLocator.init(ctx)
        val pending = goAsync()
        AppScope.io.launch {
            try {
                val db = ServiceLocator.db
                val id = intent.data?.lastPathSegment?.toLongOrNull() ?: intent.getLongExtra("id", -1)
                when (intent.action) {
                    ReminderScheduler.ACTION_HABIT -> {
                        val h = db.habitDao().habit(id)
                        if (h != null && ServiceLocator.settings.modHabit) {
                            if (h.endMinutes < 0) {
                                // Only a start time: a plain reminder.
                                Notifs.post(ctx, Notifs.ID_REMIND_BASE + (h.id % 5000).toInt(), habitNotification(ctx, h, "Time to start: ${h.name}", "Tap Done when you have finished."))
                            }
                            // With a finish time, syncing starts the running session (notification, countdown, sound mode).
                            ReminderScheduler.scheduleHabit(ctx, h)
                        }
                    }
                    ReminderScheduler.ACTION_HABIT_END -> {
                        val h = db.habitDao().habit(id)
                        if (h != null) {
                            HabitRunner.finishWindow(ctx, h)
                            val doneToday = db.habitDao().logFor(h.id, LocalDate.now().toString())?.status == 0
                            if (!doneToday && ServiceLocator.settings.modHabit) {
                                Notifs.post(ctx, Notifs.ID_REMIND_BASE + (h.id % 5000).toInt(), habitNotification(ctx, h, "Time is up: ${h.name}", "Did you finish? Tap Done to log it."))
                            }
                            if (ServiceLocator.settings.modHabit) ReminderScheduler.scheduleHabit(ctx, h)
                        }
                    }
                    ReminderScheduler.ACTION_HABIT_DONE -> {
                        val today = LocalDate.now().toString()
                        db.habitDao().upsertLog(HabitLog(habitId = id, date = today, status = 0)
                            .let { l -> db.habitDao().logFor(id, today)?.let { old -> l.copy(id = old.id) } ?: l })
                        Notifs.cancel(ctx, Notifs.ID_REMIND_BASE + (id % 5000).toInt())
                        db.habitDao().habit(id)?.let { HabitRunner.finishEarly(ctx, it) }
                    }
                    ReminderScheduler.ACTION_TASK -> {
                        val t = db.taskDao().get(id)
                        if (t != null && !t.done && ServiceLocator.settings.modTodo) {
                            Notifs.post(
                                ctx, Notifs.ID_REMIND_BASE + 5000 + (t.id % 5000).toInt(),
                                NotificationCompat.Builder(ctx, Notifs.CH_REMIND)
                                    .setSmallIcon(R.drawable.ic_stat)
                                    .setContentTitle(t.title)
                                    .setContentText("Task due now.")
                                    .setAutoCancel(true)
                                    .setContentIntent(Notifs.openApp(ctx))
                                    .build(),
                            )
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

private fun habitNotification(ctx: Context, h: Habit, title: String, text: String): android.app.Notification {
    val done = Intent(ctx, ReminderReceiver::class.java)
        .setAction(ReminderScheduler.ACTION_HABIT_DONE)
        .setData(Uri.parse("buddy://habitdone/${h.id}"))
    val donePi = PendingIntent.getBroadcast(ctx, 0, done, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return NotificationCompat.Builder(ctx, Notifs.CH_REMIND)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .setContentIntent(Notifs.openApp(ctx))
        .addAction(0, "Done", donePi)
        .build()
}
