package com.arghya.buddyonthego.domain

import kotlin.math.max
import kotlin.math.min

enum class RouteEvent { OFF_ROUTE, BACK_ON_ROUTE, WRONG_DIRECTION, DESTINATION }

/** Route rules RTE-1 .. RTE-4, RTE-7. Pure Kotlin, no Android imports. */
class RouteGuard(
    private val pts: List<LatLng>,
    private val corridor: Double = 250.0,
    destRadius: Double = 500.0,
    private val wrongDirectionOn: Boolean = true,
    leadMode: Int = 0,
    leadValue: Double = 0.0,
    private val walkingPace: Double = 1.5,
) {
    private val cum = DoubleArray(pts.size)
    private val dest = pts.last()
    private val destEngine = GpsAlarmEngine(dest.lat, dest.lng, destRadius, 0, leadMode, leadValue)
    private var lastSeg = -1
    private var offCount = 0
    private var offStart = 0L
    private var offActive = false
    private var inCount = 0
    private var maxProgress = 0.0
    private var wrongActive = false

    var progress = 0.0
        private set
    var offDistance = 0.0
        private set
    var distanceToDest = Double.NaN
        private set
    val totalLength: Double get() = cum.last()
    val etaSec: Double get() = destEngine.etaSec

    init {
        require(pts.size >= 2) { "A route needs at least 2 points" }
        for (i in 1 until pts.size) {
            cum[i] = cum[i - 1] + Geo.haversine(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng)
        }
    }

    private class Proj(val dist: Double, val progress: Double, val seg: Int)

    private fun search(f: Fix, from: Int, to: Int): Proj {
        var best: Proj? = null
        for (i in from..to) {
            val (d, t) = Geo.segmentProjection(f.lat, f.lng, pts[i].lat, pts[i].lng, pts[i + 1].lat, pts[i + 1].lng)
            if (best == null || d < best.dist) {
                best = Proj(d, cum[i] + t * (cum[i + 1] - cum[i]), i)
            }
        }
        return best!!
    }

    private fun project(f: Fix): Proj {
        val last = pts.size - 2
        if (lastSeg < 0) return search(f, 0, last)
        val windowed = search(f, max(0, lastSeg - 5), min(last, lastSeg + 40))
        return if (windowed.dist > corridor * 3) search(f, 0, last) else windowed
    }

    fun onFix(f: Fix): List<RouteEvent> {
        val out = ArrayList<RouteEvent>()
        val pr = project(f)
        lastSeg = pr.seg
        progress = pr.progress
        offDistance = pr.dist
        distanceToDest = Geo.haversine(f.lat, f.lng, dest.lat, dest.lng)

        if (destEngine.onFix(f)) out.add(RouteEvent.DESTINATION)

        val moving = f.speed >= walkingPace
        val outside = pr.dist > corridor + f.acc
        if (outside && moving) {
            if (offCount == 0) offStart = f.timeMs
            offCount++
            inCount = 0
            if (!offActive && offCount >= 3 && f.timeMs - offStart >= 30_000) {
                offActive = true
                out.add(RouteEvent.OFF_ROUTE)
            }
        } else if (!outside) {
            offCount = 0
            if (offActive) {
                inCount++
                if (inCount >= 2) {
                    offActive = false
                    inCount = 0
                    out.add(RouteEvent.BACK_ON_ROUTE)
                }
            }
        } else {
            offCount = 0 // outside but not moving: junction stop, not a deviation
        }

        if (wrongDirectionOn) {
            maxProgress = max(maxProgress, progress)
            val drop = maxProgress - progress
            val segBearing = Geo.bearing(pts[pr.seg].lat, pts[pr.seg].lng, pts[pr.seg + 1].lat, pts[pr.seg + 1].lng)
            val opposing = f.bearing >= 0 && Geo.angleDiff(f.bearing.toDouble(), segBearing) > 150.0
            val limit = if (opposing) 100.0 else 200.0
            if (moving && drop > limit && !wrongActive) {
                wrongActive = true
                out.add(RouteEvent.WRONG_DIRECTION)
            }
            if (wrongActive && drop < 50.0) wrongActive = false
        }
        return out
    }
}
