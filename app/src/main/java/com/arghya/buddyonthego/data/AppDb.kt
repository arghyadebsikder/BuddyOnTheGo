package com.arghya.buddyonthego.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Alarm::class, GpsAlarm::class, Route::class, SavedPlace::class, Habit::class,
        HabitLog::class, Task::class, EventLog::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun gpsAlarmDao(): GpsAlarmDao
    abstract fun routeDao(): RouteDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun habitDao(): HabitDao
    abstract fun taskDao(): TaskDao
    abstract fun eventLogDao(): EventLogDao

    companion object {
        fun build(ctx: Context): AppDb =
            Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "buddy.db").build()
    }
}
