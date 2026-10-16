package com.arghya.buddyonthego.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute") fun observeAll(): Flow<List<Alarm>>
    @Query("SELECT * FROM alarms") suspend fun getAll(): List<Alarm>
    @Query("SELECT * FROM alarms WHERE id = :id") suspend fun get(id: Long): Alarm?
    @Upsert suspend fun upsert(a: Alarm): Long
    @Query("DELETE FROM alarms WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface GpsAlarmDao {
    @Query("SELECT * FROM gps_alarms ORDER BY id DESC") fun observeAll(): Flow<List<GpsAlarm>>
    @Query("SELECT * FROM gps_alarms") suspend fun getAll(): List<GpsAlarm>
    @Query("SELECT * FROM gps_alarms WHERE id = :id") suspend fun get(id: Long): GpsAlarm?
    @Upsert suspend fun upsert(a: GpsAlarm): Long
    @Query("DELETE FROM gps_alarms WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes ORDER BY id DESC") fun observeAll(): Flow<List<Route>>
    @Query("SELECT * FROM routes") suspend fun getAll(): List<Route>
    @Query("SELECT * FROM routes WHERE id = :id") suspend fun get(id: Long): Route?
    @Upsert suspend fun upsert(r: Route): Long
    @Query("DELETE FROM routes WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface SavedPlaceDao {
    @Query("SELECT * FROM saved_places ORDER BY name") fun observeAll(): Flow<List<SavedPlace>>
    @Query("SELECT * FROM saved_places") suspend fun getAll(): List<SavedPlace>
    @Upsert suspend fun upsert(p: SavedPlace): Long
    @Query("DELETE FROM saved_places WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits WHERE archived = 0 ORDER BY id") fun observeHabits(): Flow<List<Habit>>
    @Query("SELECT * FROM habits") suspend fun allHabits(): List<Habit>
    @Query("SELECT * FROM habits WHERE id = :id") suspend fun habit(id: Long): Habit?
    @Upsert suspend fun upsertHabit(h: Habit): Long
    @Query("DELETE FROM habits WHERE id = :id") suspend fun deleteHabit(id: Long)

    @Query("SELECT * FROM habit_logs") fun observeLogs(): Flow<List<HabitLog>>
    @Query("SELECT * FROM habit_logs") suspend fun allLogs(): List<HabitLog>
    @Query("SELECT * FROM habit_logs WHERE habitId = :id AND date = :date LIMIT 1")
    suspend fun logFor(id: Long, date: String): HabitLog?
    @Upsert suspend fun upsertLog(l: HabitLog): Long
    @Query("DELETE FROM habit_logs WHERE habitId = :id AND date = :date") suspend fun deleteLog(id: Long, date: String)
    @Query("DELETE FROM habit_logs WHERE habitId = :id") suspend fun deleteLogsFor(id: Long)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY done, sortOrder, id")
    fun observeAll(): Flow<List<Task>>
    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM tasks") suspend fun maxSortOrder(): Long
    @Query("UPDATE tasks SET sortOrder = :order WHERE id = :id") suspend fun setSortOrder(id: Long, order: Long)
    @Query("UPDATE tasks SET priority = :priority WHERE id = :id") suspend fun setPriority(id: Long, priority: Int)
    @Query("SELECT * FROM tasks") suspend fun getAll(): List<Task>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun get(id: Long): Task?
    @Upsert suspend fun upsert(t: Task): Long
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :q || '%' OR notes LIKE '%' || :q || '%'")
    suspend fun search(q: String): List<Task>
}

@Dao
interface EventLogDao {
    @Upsert suspend fun insert(e: EventLog): Long
    @Query("SELECT * FROM event_log ORDER BY time DESC LIMIT 100") suspend fun recent(): List<EventLog>
    @Query("SELECT * FROM event_log ORDER BY time DESC LIMIT 100") fun observeRecent(): Flow<List<EventLog>>
}
