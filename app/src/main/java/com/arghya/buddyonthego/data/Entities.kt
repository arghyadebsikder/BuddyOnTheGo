package com.arghya.buddyonthego.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "alarms")
data class Alarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int = 7,
    val minute: Int = 0,
    /** bit 0 = Monday ... bit 6 = Sunday; 0 = one-time */
    val daysMask: Int = 0,
    val label: String = "",
    val enabled: Boolean = true,
    /** 0 standard, 1 strong */
    val vibrationPattern: Int = 0,
    val volumeRampSeconds: Int = 20,
    val snoozeMinutes: Int = 9,
    val maxSnoozes: Int = 3,
    /** 0 hold, 1 math, 2 shake, 3 tap (no challenge) */
    val challengeType: Int = 0,
    val skipNextDate: String? = null,
    /** Math challenge level: 0 easy, 1 medium, 2 hard (v0.0.3) */
    @ColumnInfo(defaultValue = "1") val mathDifficulty: Int = 1,
)

@Entity(tableName = "gps_alarms")
data class GpsAlarm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val destLat: Double = 0.0,
    val destLng: Double = 0.0,
    val radiusMeters: Int = 500,
    /** 0 arrive, 1 leave, 2 pass */
    val triggerType: Int = 0,
    /** 0 distance, 1 minutes */
    val leadMode: Int = 0,
    val leadValue: Int = 0,
    val enabled: Boolean = true,
)

@Entity(tableName = "routes")
data class Route(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val encodedPolyline: String = "",
    val corridorMeters: Int = 250,
    val destRadiusMeters: Int = 500,
    val wrongDirectionAlarm: Boolean = true,
    /** drawn, recorded, imported, auto */
    val createdFrom: String = "recorded",
)

@Entity(tableName = "saved_places")
data class SavedPlace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val radiusMeters: Int = 500,
    /** home, work, station, other */
    val kind: String = "other",
)

@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    /** Unused since v0.0.3 (icons were removed); kept so the database and old backups stay compatible. */
    val icon: String = "✓",
    val colorIndex: Int = 0,
    /** 0 daily, 1 weekdays, 2 times per week */
    val scheduleType: Int = 0,
    val daysMask: Int = 127,
    val targetPerWeek: Int = 3,
    /** Start time, minutes from midnight, -1 = none. (Column name kept from the old "reminder".) */
    val reminderMinutes: Int = -1,
    /** Finish time, minutes from midnight, -1 = none (then it is only a reminder). v0.0.3 */
    @ColumnInfo(defaultValue = "-1") val endMinutes: Int = -1,
    /** Phone sound while the task runs: 0 general, 1 meeting (vibrate), 2 silent. v0.0.3 */
    @ColumnInfo(defaultValue = "0") val soundMode: Int = 0,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "habit_logs", indices = [Index(value = ["habitId", "date"], unique = true)])
data class HabitLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    /** ISO date, yyyy-MM-dd */
    val date: String,
    /** 0 done, 1 skipped */
    val status: Int = 0,
)

@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val notes: String = "",
    val dueAt: Long? = null,
    /** 1 normal, 2 high (starred). 0 is treated like 1. */
    val priority: Int = 1,
    /** 0 none, 1 daily, 2 weekly, 3 monthly */
    val repeatRule: Int = 0,
    val reminder: Boolean = false,
    val done: Boolean = false,
    val doneAt: Long? = null,
    /** Manual order set by dragging, smaller first. v0.0.3 */
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
)

@Entity(tableName = "event_log")
data class EventLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val type: String = "",
    val detail: String = "",
)
