package com.arghya.buddyonthego.domain

/** Section 4.9: coordinates and shared links that can be parsed with no network. */
object CoordParser {
    private const val NUM = "-?\\d{1,3}(?:\\.\\d+)?"
    private val pair = Regex("($NUM)\\s*[,;/ ]\\s*($NUM)")
    private val at = Regex("@($NUM),($NUM)")
    private val query = Regex("[?&](?:q|ll|query|destination|daddr)=($NUM)(?:,|%2C)\\s*($NUM)")
    private val bang = Regex("!3d($NUM)!4d($NUM)")
    private val osmMap = Regex("#map=\\d+/($NUM)/($NUM)")
    private val osmMark = Regex("mlat=($NUM)&mlon=($NUM)")
    private val dms = Regex("(\\d{1,3})\\s*[°d]\\s*(\\d{1,2})\\s*['′m]\\s*(\\d{1,2}(?:\\.\\d+)?)\\s*[\"″s]?\\s*([NSEW])", RegexOption.IGNORE_CASE)

    fun isShortLink(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.contains("maps.app.goo.gl") || t.contains("goo.gl/maps")
    }

    fun parse(input: String): LatLng? {
        val text = input.trim()
        if (text.isEmpty()) return null
        parseDms(text)?.let { return it }
        val regexes = listOf(bang, at, query, osmMap, osmMark)
        for (r in regexes) {
            r.find(text)?.let { m -> make(m.groupValues[1], m.groupValues[2])?.let { return it } }
        }
        if (text.startsWith("geo:", ignoreCase = true)) {
            val body = text.substring(4).substringBefore('?').substringBefore(';')
            pair.find(body)?.let { m -> make(m.groupValues[1], m.groupValues[2])?.let { return it } }
        }
        // Bare "lat, lng"
        val bare = Regex("^\\s*($NUM)\\s*[,;\\s]\\s*($NUM)\\s*$").find(text)
        if (bare != null) return make(bare.groupValues[1], bare.groupValues[2])
        return null
    }

    private fun parseDms(text: String): LatLng? {
        val found = dms.findAll(text).toList()
        if (found.size < 2) return null
        var lat: Double? = null
        var lng: Double? = null
        for (m in found.take(2)) {
            val v = m.groupValues[1].toDouble() + m.groupValues[2].toDouble() / 60 + m.groupValues[3].toDouble() / 3600
            when (m.groupValues[4].uppercase()) {
                "N" -> lat = v
                "S" -> lat = -v
                "E" -> lng = v
                "W" -> lng = -v
            }
        }
        return if (lat != null && lng != null && valid(lat, lng)) LatLng(lat, lng) else null
    }

    private fun make(a: String, b: String): LatLng? {
        val lat = a.toDoubleOrNull() ?: return null
        val lng = b.toDoubleOrNull() ?: return null
        return if (valid(lat, lng)) LatLng(lat, lng) else null
    }

    private fun valid(lat: Double, lng: Double) = lat in -90.0..90.0 && lng in -180.0..180.0
}
