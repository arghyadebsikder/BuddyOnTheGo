package com.arghya.buddyonthego.domain

import kotlin.math.roundToInt

object Polyline {
    fun encode(points: List<LatLng>): String {
        val sb = StringBuilder()
        var pLat = 0
        var pLng = 0
        for (p in points) {
            val lat = (p.lat * 1e5).roundToInt()
            val lng = (p.lng * 1e5).roundToInt()
            enc(lat - pLat, sb)
            enc(lng - pLng, sb)
            pLat = lat
            pLng = lng
        }
        return sb.toString()
    }

    private fun enc(value: Int, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append((((0x20 or (v and 0x1f)) + 63)).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toChar())
    }

    fun decode(s: String): List<LatLng> {
        val out = ArrayList<LatLng>()
        var i = 0
        var lat = 0
        var lng = 0
        while (i < s.length) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = s[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            shift = 0
            result = 0
            do {
                b = s[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out.add(LatLng(lat / 1e5, lng / 1e5))
        }
        return out
    }

    /** Ramer-Douglas-Peucker simplification with a tolerance in meters. */
    fun simplify(points: List<LatLng>, toleranceM: Double): List<LatLng> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(Pair(0, points.lastIndex))
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var maxD = 0.0
            var idx = -1
            for (i in s + 1 until e) {
                val (d, _) = Geo.segmentProjection(
                    points[i].lat, points[i].lng,
                    points[s].lat, points[s].lng,
                    points[e].lat, points[e].lng,
                )
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx != -1 && maxD > toleranceM) {
                keep[idx] = true
                stack.addLast(Pair(s, idx))
                stack.addLast(Pair(idx, e))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    fun lengthMeters(points: List<LatLng>): Double {
        var sum = 0.0
        for (i in 0 until points.size - 1) sum += Geo.haversine(points[i].lat, points[i].lng, points[i + 1].lat, points[i + 1].lng)
        return sum
    }
}
