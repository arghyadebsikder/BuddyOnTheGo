package com.arghya.buddyonthego

import com.arghya.buddyonthego.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class LogicTests {
    @Test fun haversineOneDegreeLatitude() {
        val d = Geo.haversine(0.0, 0.0, 1.0, 0.0)
        assertEquals(111195.0, d, 300.0)
    }

    @Test fun oneTimeAlarmRollsToTomorrow() {
        val utc = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 10, 12, 10, 0, 0, 0, utc).toInstant().toEpochMilli()
        val t = NextTrigger.next(9, 0, 0, now, utc)
        assertEquals(now + 23 * 3600_000L, t)
    }

    @Test fun repeatingAlarmSkipsSkippedDate() {
        val utc = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 10, 12, 6, 0, 0, 0, utc).toInstant().toEpochMilli() // Monday
        val withoutSkip = NextTrigger.next(7, 0, 127, now, utc)!!
        val withSkip = NextTrigger.next(7, 0, 127, now, utc, LocalDate.of(2026, 10, 12))!!
        assertEquals(withoutSkip + 24 * 3600_000L, withSkip)
    }

    @Test fun streakCountsConsecutiveDays() {
        val today = LocalDate.of(2026, 10, 12)
        val done = setOf(today, today.minusDays(1), today.minusDays(2))
        val r = Streaks.compute(0, 127, 3, done, emptySet(), today, false)
        assertEquals(3, r.current)
        assertEquals(3, r.best)
    }

    @Test fun streakBreaksOnMissedDay() {
        val today = LocalDate.of(2026, 10, 12)
        val done = setOf(today.minusDays(4), today.minusDays(3), today)
        val r = Streaks.compute(0, 127, 3, done, emptySet(), today, false)
        assertEquals(1, r.current)
        assertEquals(2, r.best)
    }

    @Test fun coordinateParsing() {
        assertEquals(LatLng(23.8103, 90.4125), CoordParser.parse("23.8103, 90.4125"))
        assertEquals(LatLng(23.81, 90.41), CoordParser.parse("https://www.google.com/maps/place/X/@23.81,90.41,15z"))
        assertEquals(LatLng(23.8, 90.4), CoordParser.parse("geo:23.8,90.4"))
        assertNull(CoordParser.parse("hello"))
        assertTrue(CoordParser.isShortLink("https://maps.app.goo.gl/abc"))
    }

    @Test fun polylineRoundTripAndSimplify() {
        val pts = listOf(LatLng(23.0, 90.0), LatLng(23.0005, 90.0), LatLng(23.001, 90.0), LatLng(23.01, 90.01))
        val back = Polyline.decode(Polyline.encode(pts))
        assertEquals(pts.size, back.size)
        assertEquals(pts[3].lat, back[3].lat, 1e-5)
        val simple = Polyline.simplify(pts, 10.0)
        assertTrue(simple.size < pts.size)
    }

    @Test fun arrivalRingsWhenInsideRadius() {
        val e = GpsAlarmEngine(23.0, 90.0, 500.0)
        assertFalse(e.onFix(Fix(23.1, 90.0, 10f, 10f, 0)))
        assertTrue(e.onFix(Fix(23.0, 90.0, 10f, 10f, 5000)))
    }

    @Test fun offRouteNeedsThreeFixesOver30Seconds() {
        val g = RouteGuard(listOf(LatLng(23.0, 90.0), LatLng(23.1, 90.0)), corridor = 250.0)
        val events = ArrayList<RouteEvent>()
        for (i in 0..3) events += g.onFix(Fix(23.01, 90.01, 10f, 5f, i * 15_000L))
        assertTrue(events.contains(RouteEvent.OFF_ROUTE))
    }

    @Test fun onRouteNeverAlarms() {
        val g = RouteGuard(listOf(LatLng(23.0, 90.0), LatLng(23.1, 90.0)), corridor = 250.0)
        val events = ArrayList<RouteEvent>()
        for (i in 0..5) events += g.onFix(Fix(23.0 + i * 0.001, 90.0, 10f, 8f, i * 15_000L, 0f))
        assertTrue(events.isEmpty())
    }

    @Test fun wrongDirectionIsDetected() {
        val g = RouteGuard(listOf(LatLng(23.0, 90.0), LatLng(23.1, 90.0)), corridor = 250.0)
        val events = ArrayList<RouteEvent>()
        events += g.onFix(Fix(23.02, 90.0, 10f, 8f, 0, 0f))
        events += g.onFix(Fix(23.019, 90.0, 10f, 8f, 15_000, 180f))
        events += g.onFix(Fix(23.017, 90.0, 10f, 8f, 30_000, 180f))
        assertTrue(events.contains(RouteEvent.WRONG_DIRECTION))
    }

    // ---- v0.0.3 ----

    @Test fun habitWindowActiveInsideAndNotOutside() {
        val utc = ZoneId.of("UTC")
        fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 12, h, m, 0, 0, utc).toInstant().toEpochMilli() // Monday
        // 09:00 to 09:30
        assertNull(HabitWindow.activeAt(540, 570, 127, at(8, 59), utc))
        val w = HabitWindow.activeAt(540, 570, 127, at(9, 10), utc)!!
        assertEquals(at(9, 0), w.startMs)
        assertEquals(at(9, 30), w.endMs)
        assertEquals(1f / 3f, w.progress(at(9, 10)), 0.01f)
        assertNull(HabitWindow.activeAt(540, 570, 127, at(9, 30), utc))
    }

    @Test fun habitWindowCrossesMidnight() {
        val utc = ZoneId.of("UTC")
        // 23:00 to 01:00, running on Monday only (bit 0)
        val mondayNight = ZonedDateTime.of(2026, 10, 12, 23, 30, 0, 0, utc).toInstant().toEpochMilli()
        val tuesdayEarly = ZonedDateTime.of(2026, 10, 13, 0, 30, 0, 0, utc).toInstant().toEpochMilli()
        val tuesdayNight = ZonedDateTime.of(2026, 10, 13, 23, 30, 0, 0, utc).toInstant().toEpochMilli()
        assertNotNull(HabitWindow.activeAt(23 * 60, 60, 1, mondayNight, utc))
        assertNotNull(HabitWindow.activeAt(23 * 60, 60, 1, tuesdayEarly, utc))
        assertNull(HabitWindow.activeAt(23 * 60, 60, 1, tuesdayNight, utc))
        assertEquals(120, HabitWindow.lengthMinutes(23 * 60, 60))
    }

    @Test fun mathAnswersAreCorrectAndInRange() {
        val rnd = kotlin.random.Random(42)
        for (level in 0..2) repeat(200) {
            val p = MathChallenge.generate(level, rnd)
            assertTrue(p.answer >= 0)
            val parts = p.text.split(" ")
            val a = parts[0].toInt()
            val b = parts[2].toInt()
            val expected = when (parts[1]) {
                "+" -> if (parts.size == 3) a + b else 0
                "-" -> a - b
                else -> a * b + parts[4].toInt()
            }
            if (parts[1] != "+") assertEquals(expected, p.answer) else assertEquals(a + b, p.answer)
        }
    }

    @Test fun autoThemeSwitchesAtTheChosenTimes() {
        assertFalse(ThemeSchedule.isDark(12 * 60, 390, 1110))
        assertTrue(ThemeSchedule.isDark(23 * 60, 390, 1110))
        assertTrue(ThemeSchedule.isDark(5 * 60, 390, 1110))
        assertFalse(ThemeSchedule.isDark(390, 390, 1110))
        assertTrue(ThemeSchedule.isDark(1110, 390, 1110))
    }

    /** NET-2 / NET-10: HTTP classes only inside the net package; tracking never touches NetworkGate. */
    @Test fun networkIsolation() {
        val root = File("src/main/java")
        if (!root.exists()) return
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val path = f.path.replace('\\', '/')
            val text = f.readText()
            if (!path.contains("/net/")) assertFalse("$path imports okhttp", text.contains("okhttp3"))
            if (path.contains("/gps/")) assertFalse("$path uses NetworkGate", text.contains("NetworkGate"))
            if (path.contains("/domain/")) assertFalse("$path imports android", text.contains("import android."))
        }
    }
}
