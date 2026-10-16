package com.arghya.buddyonthego.reminder

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager

/** Phone sound while a timed habit or task is running. */
object SoundMode {
    const val GENERAL = 0
    const val MEETING = 1
    const val SILENT = 2

    val names = listOf("General", "Meeting", "Silent")
    val hints = listOf(
        "Sound stays as it is.",
        "Phone vibrates only. No ringing.",
        "No sounds at all. Your Buddy alarms still ring.",
    )
}

/**
 * Changes the phone's sound for the length of a task and puts it back afterwards.
 * Several tasks can overlap: the strictest one wins, and the original sound is restored when the last one ends.
 * Silent needs "Do Not Disturb access". Without it, Silent falls back to vibrate.
 */
object SoundGuard {
    private const val PREFS = "soundguard"
    private const val K_ACTIVE = "active" // "id:mode,id:mode"
    private const val K_SAVED = "saved"
    private const val K_RINGER = "ringer"
    private const val K_FILTER = "filter"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPolicyAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun readActive(ctx: Context): MutableMap<Long, Int> {
        val out = LinkedHashMap<Long, Int>()
        sp(ctx).getString(K_ACTIVE, "").orEmpty().split(",").forEach { part ->
            val bits = part.split(":")
            val id = bits.getOrNull(0)?.toLongOrNull()
            val mode = bits.getOrNull(1)?.toIntOrNull()
            if (id != null && mode != null) out[id] = mode
        }
        return out
    }

    private fun writeActive(ctx: Context, map: Map<Long, Int>) {
        sp(ctx).edit().putString(K_ACTIVE, map.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
    }

    /** A task with this sound mode is now running. General means nothing to change. */
    @Synchronized
    fun begin(ctx: Context, habitId: Long, mode: Int) {
        val m = readActive(ctx)
        if (mode == SoundMode.GENERAL) m.remove(habitId) else m[habitId] = mode
        writeActive(ctx, m)
        refresh(ctx)
    }

    @Synchronized
    fun end(ctx: Context, habitId: Long) {
        val m = readActive(ctx)
        if (m.remove(habitId) != null) writeActive(ctx, m)
        refresh(ctx)
    }

    private fun refresh(ctx: Context) {
        val effective = readActive(ctx).values.maxOrNull() ?: SoundMode.GENERAL
        try {
            if (effective == SoundMode.GENERAL) restore(ctx) else apply(ctx, effective)
        } catch (_: SecurityException) {
            // Do Not Disturb access was taken away. Nothing more we can do.
        }
    }

    private fun saveOriginal(ctx: Context) {
        val s = sp(ctx)
        if (s.getBoolean(K_SAVED, false)) return
        val am = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        s.edit()
            .putBoolean(K_SAVED, true)
            .putInt(K_RINGER, am.ringerMode)
            .putInt(K_FILTER, if (nm.isNotificationPolicyAccessGranted) nm.currentInterruptionFilter else 0)
            .apply()
    }

    private fun apply(ctx: Context, mode: Int) {
        saveOriginal(ctx)
        val am = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val s = sp(ctx)
        val granted = nm.isNotificationPolicyAccessGranted
        if (mode == SoundMode.SILENT && granted) {
            // Alarms only: every sound is muted except alarms, so Buddy can still wake you.
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        } else {
            if (granted) {
                val original = s.getInt(K_FILTER, 0)
                if (original in 1..4) nm.setInterruptionFilter(original)
            }
            am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        }
    }

    private fun restore(ctx: Context) {
        val s = sp(ctx)
        if (!s.getBoolean(K_SAVED, false)) return
        val am = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val filter = s.getInt(K_FILTER, 0)
        if (nm.isNotificationPolicyAccessGranted && filter in 1..4) nm.setInterruptionFilter(filter)
        am.ringerMode = s.getInt(K_RINGER, AudioManager.RINGER_MODE_NORMAL)
        s.edit().putBoolean(K_SAVED, false).apply()
    }
}
