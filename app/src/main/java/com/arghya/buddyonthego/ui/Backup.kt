package com.arghya.buddyonthego.ui

import com.arghya.buddyonthego.ServiceLocator
import com.arghya.buddyonthego.data.*
import org.json.JSONArray
import org.json.JSONObject

/** Export / import everything as one JSON document (Q15). */
object Backup {
    suspend fun export(): String {
        val db = ServiceLocator.db
        val o = JSONObject()
        o.put("app", "BuddyOnTheGo"); o.put("version", 2)
        o.put("alarms", JSONArray(db.alarmDao().getAll().map { a -> JSONObject().put("hour", a.hour).put("minute", a.minute).put("daysMask", a.daysMask).put("label", a.label).put("enabled", a.enabled).put("vibe", a.vibrationPattern).put("ramp", a.volumeRampSeconds).put("snooze", a.snoozeMinutes).put("maxSnoozes", a.maxSnoozes).put("challenge", a.challengeType).put("mathLevel", a.mathDifficulty) }))
        o.put("gpsAlarms", JSONArray(db.gpsAlarmDao().getAll().map { a -> JSONObject().put("name", a.name).put("lat", a.destLat).put("lng", a.destLng).put("radius", a.radiusMeters).put("trigger", a.triggerType).put("leadMode", a.leadMode).put("leadValue", a.leadValue) }))
        o.put("routes", JSONArray(db.routeDao().getAll().map { r -> JSONObject().put("name", r.name).put("poly", r.encodedPolyline).put("corridor", r.corridorMeters).put("destRadius", r.destRadiusMeters).put("wrongDir", r.wrongDirectionAlarm).put("from", r.createdFrom) }))
        o.put("places", JSONArray(db.savedPlaceDao().getAll().map { p -> JSONObject().put("name", p.name).put("lat", p.lat).put("lng", p.lng).put("radius", p.radiusMeters).put("kind", p.kind) }))
        o.put("habits", JSONArray(db.habitDao().allHabits().map { h -> JSONObject().put("oldId", h.id).put("name", h.name).put("icon", h.icon).put("color", h.colorIndex).put("type", h.scheduleType).put("mask", h.daysMask).put("target", h.targetPerWeek).put("remind", h.reminderMinutes).put("end", h.endMinutes).put("sound", h.soundMode) }))
        o.put("habitLogs", JSONArray(db.habitDao().allLogs().map { l -> JSONObject().put("habit", l.habitId).put("date", l.date).put("status", l.status) }))
        o.put("tasks", JSONArray(db.taskDao().getAll().map { t -> JSONObject().put("title", t.title).put("notes", t.notes).put("due", t.dueAt ?: JSONObject.NULL).put("priority", t.priority).put("repeat", t.repeatRule).put("reminder", t.reminder).put("done", t.done).put("order", t.sortOrder) }))
        return o.toString(2)
    }

    /** Adds the backup's items to what is already on the phone. Returns the number of items added. */
    suspend fun import(text: String): Int {
        val o = JSONObject(text)
        require(o.optString("app") == "BuddyOnTheGo") { "Not a Buddy backup" }
        val db = ServiceLocator.db
        var n = 0
        fun arr(k: String): List<JSONObject> = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
        for (j in arr("alarms")) { db.alarmDao().upsert(Alarm(hour = j.getInt("hour"), minute = j.getInt("minute"), daysMask = j.getInt("daysMask"), label = j.optString("label"), enabled = j.optBoolean("enabled", true), vibrationPattern = j.optInt("vibe"), volumeRampSeconds = j.optInt("ramp", 20), snoozeMinutes = j.optInt("snooze", 9), maxSnoozes = j.optInt("maxSnoozes", 3), challengeType = j.optInt("challenge"), mathDifficulty = j.optInt("mathLevel", 1))); n++ }
        for (j in arr("gpsAlarms")) { db.gpsAlarmDao().upsert(GpsAlarm(name = j.optString("name"), destLat = j.getDouble("lat"), destLng = j.getDouble("lng"), radiusMeters = j.optInt("radius", 500), triggerType = j.optInt("trigger"), leadMode = j.optInt("leadMode"), leadValue = j.optInt("leadValue"))); n++ }
        for (j in arr("routes")) { db.routeDao().upsert(Route(name = j.optString("name"), encodedPolyline = j.getString("poly"), corridorMeters = j.optInt("corridor", 250), destRadiusMeters = j.optInt("destRadius", 500), wrongDirectionAlarm = j.optBoolean("wrongDir", true), createdFrom = j.optString("from", "imported"))); n++ }
        for (j in arr("places")) { db.savedPlaceDao().upsert(SavedPlace(name = j.optString("name"), lat = j.getDouble("lat"), lng = j.getDouble("lng"), radiusMeters = j.optInt("radius", 500), kind = j.optString("kind", "other"))); n++ }
        val idMap = HashMap<Long, Long>()
        for (j in arr("habits")) { idMap[j.getLong("oldId")] = db.habitDao().upsertHabit(Habit(name = j.optString("name"), icon = j.optString("icon", "✓"), colorIndex = j.optInt("color"), scheduleType = j.optInt("type"), daysMask = j.optInt("mask", 127), targetPerWeek = j.optInt("target", 3), reminderMinutes = j.optInt("remind", -1), endMinutes = j.optInt("end", -1), soundMode = j.optInt("sound", 0))); n++ }
        for (j in arr("habitLogs")) { val nid = idMap[j.getLong("habit")] ?: continue; db.habitDao().upsertLog(HabitLog(habitId = nid, date = j.getString("date"), status = j.optInt("status"))) }
        for (j in arr("tasks")) { db.taskDao().upsert(Task(title = j.optString("title"), notes = j.optString("notes"), dueAt = if (j.isNull("due")) null else j.getLong("due"), priority = j.optInt("priority", 1), repeatRule = j.optInt("repeat"), reminder = j.optBoolean("reminder"), done = j.optBoolean("done"), sortOrder = j.optLong("order", 0L).let { if (it == 0L) db.taskDao().maxSortOrder() + 1 else it })); n++ }
        return n
    }
}
