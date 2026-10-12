package com.arghya.buddyonthego.domain

import kotlin.math.*

data class LatLng(val lat: Double, val lng: Double)

/** One GPS fix. Times are wall-clock milliseconds. bearing < 0 means unknown. */
data class Fix(
    val lat: Double,
    val lng: Double,
    val acc: Float = 20f,
    val speed: Float = 0f,
    val timeMs: Long = 0L,
    val bearing: Float = -1f,
)

object Geo {
    private const val R = 6371000.0

    fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(a)))
    }

    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lng2 - lng1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180) 360 - d else d
    }

    /** Distance in meters from point p to segment a-b, and the clamped position t (0..1) along it. */
    fun segmentProjection(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double,
    ): Pair<Double, Double> {
        val cosLat = cos(Math.toRadians(pLat))
        fun x(lng: Double) = Math.toRadians(lng - pLng) * cosLat * R
        fun y(lat: Double) = Math.toRadians(lat - pLat) * R
        val ax = x(aLng); val ay = y(aLat)
        val bx = x(bLng); val by = y(bLat)
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
        val tc = t.coerceIn(0.0, 1.0)
        return Pair(hypot(ax + tc * dx, ay + tc * dy), tc)
    }
}

/**
 * Trigger rules for a single destination (GPS-3 .. GPS-7).
 * trigger: 0 = arrive, 1 = leave, 2 = pass. leadMode: 0 = distance, 1 = minutes.
 */
class GpsAlarmEngine(
    private val destLat: Double,
    private val destLng: Double,
    private val radius: Double,
    private val trigger: Int = 0,
    private val leadMode: Int = 0,
    private val leadValue: Double = 0.0,
    private val speedFloor: Double = 1.0,
) {
    private var qualifying = 0
    private var insideConfirmed = false
    private var insideCount = 0
    private var prev: Fix? = null
    private val recent = ArrayDeque<Fix>()

    var distance: Double = Double.NaN
        private set
    var etaSec: Double = Double.NaN
        private set

    private fun confirmed(f: Fix) = qualifying >= 2 || (qualifying >= 1 && f.acc < 30f)

    /** Returns true when the alarm should ring for this fix. */
    fun onFix(f: Fix): Boolean {
        val d = Geo.haversine(f.lat, f.lng, destLat, destLng)
        distance = d
        recent.addLast(f)
        while (recent.isNotEmpty() && f.timeMs - recent.first().timeMs > 120_000) recent.removeFirst()
        val smoothed = if (recent.isEmpty()) 0.0 else recent.map { it.speed.toDouble() }.average()
        etaSec = d / max(smoothed, speedFloor)
        val effective = d - f.acc
        val before = prev
        prev = f

        return when (trigger) {
            1 -> { // leave
                if (!insideConfirmed) {
                    if (effective < radius) insideCount++ else insideCount = 0
                    if (insideCount >= 2 || (insideCount >= 1 && f.acc < 30f)) insideConfirmed = true
                    false
                } else {
                    if (effective > radius) qualifying++ else qualifying = 0
                    confirmed(f)
                }
            }
            2 -> { // pass
                if (before == null) {
                    effective <= radius
                } else {
                    val (segDist, _) = Geo.segmentProjection(destLat, destLng, before.lat, before.lng, f.lat, f.lng)
                    segDist <= radius || effective <= radius
                }
            }
            else -> { // arrive
                val threshold = if (leadMode == 0) max(radius, leadValue) else radius
                val byDistance = effective <= threshold
                val byEta = leadMode == 1 && etaSec <= leadValue * 60.0
                if (byDistance || byEta) qualifying++ else qualifying = 0
                confirmed(f)
            }
        }
    }
}
