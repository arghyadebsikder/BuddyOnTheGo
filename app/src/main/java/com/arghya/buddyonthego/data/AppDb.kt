package com.arghya.buddyonthego.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Alarm::class, GpsAlarm::class, Route::class, SavedPlace::class, Habit::class,
        HabitLog::class, Task::class, EventLog::class],
    version = 2,
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
            Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "buddy.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v0.0.3: math level, habit finish time and sound mode, manual task order. Keeps all existing data. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alarms ADD COLUMN mathDifficulty INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE habits ADD COLUMN endMinutes INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE habits ADD COLUMN soundMode INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE tasks SET sortOrder = id")
            }
        }
    }
}
