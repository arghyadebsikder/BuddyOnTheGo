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
