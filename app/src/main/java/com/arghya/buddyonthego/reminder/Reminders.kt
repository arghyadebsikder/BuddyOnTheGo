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
    const val ACTION_HABIT = "com.arghya.buddyonthego.REMIND_HABIT"
    const val ACTION_TASK = "com.arghya.buddyonthego.REMIND_TASK"
    const val ACTION_HABIT_DONE = "com.arghya.buddyonthego.HABIT_DONE"

    private fun habitPi(ctx: Context, id: Long) = Exact.broadcast(ctx, ReminderReceiver::class.java, ACTION_HABIT, "buddy://habit/$id")

    private fun taskPi(ctx: Context, id: Long) = Exact.broadcast(ctx, ReminderReceiver::class.java, ACTION_TASK, "buddy://task/$id")

    fun scheduleHabit(ctx: Context, h: Habit) {
        cancelHabit(ctx, h.id)
        if (h.reminderMinutes < 0 || h.archived) return
        val mask = if (h.scheduleType == 1 && h.daysMask != 0) h.daysMask else 127
        val t = NextTrigger.next(h.reminderMinutes / 60, h.reminderMinutes % 60, mask, System.currentTimeMillis(), ZoneId.systemDefault()) ?: return
        Exact.set(ctx, t, habitPi(ctx, h.id))
    }

    fun cancelHabit(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(habitPi(ctx, id))
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
            if (s.modHabit) ReminderScheduler.scheduleHabit(ctx, h) else ReminderScheduler.cancelHabit(ctx, h.id)
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
                            val done = Intent(ctx, ReminderReceiver::class.java)
                                .setAction(ReminderScheduler.ACTION_HABIT_DONE)
                                .setData(Uri.parse("buddy://habitdone/${h.id}"))
                            val donePi = PendingIntent.getBroadcast(ctx, 0, done, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            Notifs.post(
                                ctx, Notifs.ID_REMIND_BASE + (h.id % 5000).toInt(),
                                NotificationCompat.Builder(ctx, Notifs.CH_REMIND)
                                    .setSmallIcon(R.drawable.ic_stat)
                                    .setContentTitle("${h.icon} ${h.name}")
                                    .setContentText("Time for your habit.")
                                    .setAutoCancel(true)
                                    .setContentIntent(Notifs.openApp(ctx))
                                    .addAction(0, "Done", donePi)
                                    .build(),
                            )
                            ReminderScheduler.scheduleHabit(ctx, h)
                        }
                    }
                    ReminderScheduler.ACTION_HABIT_DONE -> {
                        val today = LocalDate.now().toString()
                        db.habitDao().upsertLog(HabitLog(habitId = id, date = today, status = 0)
                            .let { l -> db.habitDao().logFor(id, today)?.let { old -> l.copy(id = old.id) } ?: l })
                        Notifs.cancel(ctx, Notifs.ID_REMIND_BASE + (id % 5000).toInt())
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
