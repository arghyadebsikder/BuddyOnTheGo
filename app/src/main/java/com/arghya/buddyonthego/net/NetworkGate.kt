package com.arghya.buddyonthego.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.arghya.buddyonthego.BuildConfig
import com.arghya.buddyonthego.data.K
import com.arghya.buddyonthego.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.time.YearMonth
import java.util.concurrent.TimeUnit

/** Online features from table 3.3 that are implemented in this build. */
enum class Feature(val id: String, val label: String, val toggleKey: String, val minIntervalMs: Long, val timeoutSec: Long) {
    PLACE_SEARCH("o3", "Place search", K.ON_SEARCH, 1_000L, 10),
    RESOLVE_LINK("o7", "Resolve short map links", K.ON_LINKS, 1_000L, 10),
}

enum class FailReason(val message: String) {
    MASTER_OFF("Turn on Online Mode in Settings to use this."),
    FEATURE_OFF("This online feature is switched off in Settings, Connectivity."),
    NO_CONNECTION("No internet connection."),
    WIFI_ONLY("Large downloads wait for Wi-Fi."),
    RATE_LIMITED("Please wait a second and try again."),
    TIMEOUT("The service took too long to answer."),
    ERROR("The service could not be reached."),
}

sealed class NetResult {
    data class Ok(val body: String, val finalUrl: String) : NetResult()
    data class Fail(val reason: FailReason) : NetResult()
}

/**
 * NET-2: the ONLY class that owns an HTTP client. NET-3: gate order is
 * master switch, feature switch, connection, Wi-Fi rule, rate limiter.
 * NET-10: the tracking service never imports this class.
 */
class NetworkGate(private val ctx: Context, private val settings: Settings) {
    private val client = OkHttpClient.Builder().followRedirects(true).build()
    private val lastCall = HashMap<String, Long>()
    private val counters = ctx.applicationContext.getSharedPreferences("net_bytes", Context.MODE_PRIVATE)
    private val userAgent = "BuddyOnTheGo/${BuildConfig.VERSION_NAME} (${BuildConfig.CONTACT})"

    private fun connected(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun unmetered(): Boolean =
        ctx.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false

    fun check(feature: Feature, largeDownload: Boolean = false): FailReason? {
        if (!settings.online) return FailReason.MASTER_OFF
        if (!settings.bool(feature.toggleKey, true)) return FailReason.FEATURE_OFF
        if (!connected()) return FailReason.NO_CONNECTION
        if (largeDownload && settings.bool(K.WIFI_ONLY, true) && !unmetered()) return FailReason.WIFI_ONLY
        val now = System.currentTimeMillis()
        synchronized(lastCall) {
            val last = lastCall[feature.id] ?: 0L
            if (now - last < feature.minIntervalMs) return FailReason.RATE_LIMITED
            lastCall[feature.id] = now
        }
        return null
    }

    suspend fun get(feature: Feature, url: String, readBody: Boolean = true): NetResult {
        check(feature)?.let { return NetResult.Fail(it) }
        return withContext(Dispatchers.IO) {
            val c = client.newBuilder().callTimeout(feature.timeoutSec, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(url).header("User-Agent", userAgent).get().build()
            var attempt = 0
            while (true) {
                try {
                    c.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext NetResult.Fail(FailReason.ERROR)
                        val body = if (readBody) resp.body?.string().orEmpty() else ""
                        addBytes(feature, body.length.toLong() + url.length)
                        return@withContext NetResult.Ok(body, resp.request.url.toString())
                    }
                } catch (e: SocketTimeoutException) {
                    if (attempt++ >= 1) return@withContext NetResult.Fail(FailReason.TIMEOUT)
                } catch (e: IOException) {
                    if (attempt++ >= 1) return@withContext NetResult.Fail(FailReason.ERROR)
                }
                kotlinx.coroutines.delay(800)
            }
            return@withContext NetResult.Fail(FailReason.ERROR)
        }
    }

    // NET-8 data counters
    private fun monthKey(feature: Feature) = "${YearMonth.now()}:${feature.id}"
    private fun addBytes(feature: Feature, n: Long) {
        val k = monthKey(feature)
        counters.edit().putLong(k, counters.getLong(k, 0L) + n).apply()
    }
    fun bytesThisMonth(feature: Feature): Long = counters.getLong(monthKey(feature), 0L)
    fun resetCounters() = counters.edit().clear().apply()

    /** O7: follow the redirect of a short map link. */
    suspend fun resolveShortLink(url: String): NetResult = get(Feature.RESOLVE_LINK, url, readBody = false)
}

data class PlaceHit(val name: String, val detail: String, val lat: Double, val lng: Double, val source: String)

class SearchOutcome(val hits: List<PlaceHit>, val error: String?)

/** Section 4.6 online chain: Photon first, Nominatim (on submit only) as fallback. */
class PlaceSearch(private val gate: NetworkGate) {
    suspend fun search(query: String, nearLat: Double?, nearLng: Double?): SearchOutcome {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val bias = if (nearLat != null && nearLng != null) "&lat=$nearLat&lon=$nearLng" else ""
        when (val r = gate.get(Feature.PLACE_SEARCH, "https://photon.komoot.io/api/?q=$q&limit=8$bias")) {
            is NetResult.Ok -> parsePhoton(r.body)?.let { return SearchOutcome(it, null) }
            is NetResult.Fail -> if (r.reason != FailReason.ERROR && r.reason != FailReason.TIMEOUT) {
                return SearchOutcome(emptyList(), r.reason.message)
            }
        }
        return when (val r = gate.get(Feature.PLACE_SEARCH, "https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=8")) {
            is NetResult.Ok -> SearchOutcome(parseNominatim(r.body) ?: emptyList(), null)
            is NetResult.Fail -> SearchOutcome(emptyList(), r.reason.message)
        }
    }

    private fun parsePhoton(body: String): List<PlaceHit>? = try {
        val arr = JSONObject(body).getJSONArray("features")
        (0 until arr.length()).mapNotNull { i ->
            val f = arr.getJSONObject(i)
            val c = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            val name = p.optString("name").ifBlank { p.optString("street") }
            if (name.isBlank()) null else PlaceHit(
                name,
                listOf(p.optString("city"), p.optString("state"), p.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                c.getDouble(1), c.getDouble(0), "Photon",
            )
        }
    } catch (e: Exception) { null }

    private fun parseNominatim(body: String): List<PlaceHit>? = try {
        val arr = JSONArray(body)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val display = o.optString("display_name")
            PlaceHit(display.substringBefore(","), display.substringAfter(",", "").trim(), o.getDouble("lat"), o.getDouble("lon"), "Nominatim")
        }
    } catch (e: Exception) { null }
}
