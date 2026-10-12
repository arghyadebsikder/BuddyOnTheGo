package com.arghya.buddyonthego.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arghya.buddyonthego.AppScope
import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.domain.NextTrigger
import com.arghya.buddyonthego.gps.TrackingStore
import com.arghya.buddyonthego.reminder.Reschedule
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** ALM-4: AlarmManager fires this; it starts RingService and arms the next occurrence. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ServiceLocator.init(ctx)
        val id = intent.getLongExtra("id", -1)
        val count = intent.getIntExtra("snoozeCount", 0).coerceAtLeast(0)
        val isSnooze = intent.data?.lastPathSegment == "s"
        val pending = goAsync()
        AppScope.io.launch {
            try {
                val dao = ServiceLocator.db.alarmDao()
                val a = dao.get(id)
                if (a != null && ServiceLocator.settings.modAlarm) {
                    val time = String.format(Locale.US, "%02d:%02d", a.hour, a.minute)
                    RingService.start(
                        ctx, RingKind.TIME, a.label.ifBlank { "Alarm" }, "It is $time.",
                        ownerId = a.id, snoozeCount = count, maxSnoozes = a.maxSnoozes,
                        snoozeMinutes = a.snoozeMinutes, rampSec = a.volumeRampSeconds,
                        vibe = a.vibrationPattern, challenge = a.challengeType,
                    )
                    if (!isSnooze) {
                        if (a.daysMask == 0) {
                            dao.upsert(a.copy(enabled = false))
                        } else {
                            val today = NextTrigger.dateOf(System.currentTimeMillis(), ZoneId.systemDefault())
                            val skip = a.skipNextDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                            val cleaned = if (skip != null && !skip.isAfter(today)) a.copy(skipNextDate = null) else a
                            if (cleaned !== a) dao.upsert(cleaned)
                            AlarmScheduler.schedule(ctx, cleaned)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** ALM-10 and GPS-15: restore everything after boot, time change, time zone change or update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ServiceLocator.init(ctx)
        val pending = goAsync()
        AppScope.io.launch {
            try {
                Reschedule.all(ctx)
                val boot = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
                val session = TrackingStore.get(ctx)
                if (boot && session != null && ServiceLocator.settings.modGps) {
                    // D8: tap-to-resume. Keep the safety net alive meanwhile.
                    if (session.backupAt > System.currentTimeMillis()) {
                        com.arghya.buddyonthego.gps.BackupAlarms.schedule(ctx, session.backupAt)
                    }
                    Notifs.resumePrompt(ctx)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
