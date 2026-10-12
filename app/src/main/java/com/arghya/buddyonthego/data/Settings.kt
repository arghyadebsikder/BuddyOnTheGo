package com.arghya.buddyonthego.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Setting keys (section 7.3). */
object K {
    const val MOD_ALARM = "mod_alarm"
    const val MOD_GPS = "mod_gps"
    const val MOD_HABIT = "mod_habit"
    const val MOD_TODO = "mod_todo"

    const val ONLINE = "online_mode"
    const val ON_SEARCH = "online_search"
    const val ON_LINKS = "online_links"
    const val WIFI_ONLY = "wifi_only"

    const val THEME = "theme_mode" // 0 system, 1 light, 2 dark
    const val DYNAMIC = "dynamic_color"
    const val UNITS_KM = "units_km"
    const val H12 = "time_12h"

    const val GPS_MODE = "gps_mode" // 0 battery saver, 1 balanced, 2 precise
    const val WRONG_DIR = "wrong_direction"
    const val LOST_QUIET = "gps_lost_quiet_min"
    const val LOST_RING = "gps_lost_ring_min"
    const val BACKUP_TIMER = "backup_timer"
    const val RESUME_PROMPT = "resume_prompt"
    const val SILENT_RESUME = "silent_resume"

    const val AUTOSTOP_MIN = "ring_auto_stop_min"
    const val REALERT_SEC = "re_alert_sec"
    const val STREAK_GRACE = "streak_grace"

    const val ONBOARDED = "onboarded"
    const val ASKED_BATTERY = "asked_battery"
}

/** Small SharedPreferences wrapper that also exposes a change counter for Compose. */
class Settings(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick

    fun bool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    fun int(key: String, def: Int): Int = sp.getInt(key, def)
    fun str(key: String, def: String): String = sp.getString(key, def) ?: def

    fun put(key: String, value: Any) {
        sp.edit().apply {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is String -> putString(key, value)
                is Long -> putLong(key, value)
            }
        }.apply()
        _tick.value = _tick.value + 1
    }

    // Convenience accessors with the plan's defaults
    val modAlarm get() = bool(K.MOD_ALARM, true)
    val modGps get() = bool(K.MOD_GPS, true)
    val modHabit get() = bool(K.MOD_HABIT, true)
    val modTodo get() = bool(K.MOD_TODO, true)
    val online get() = bool(K.ONLINE, false)
    val km get() = bool(K.UNITS_KM, true)
    val h12 get() = bool(K.H12, true)
}
