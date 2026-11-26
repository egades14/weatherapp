package fi.lounassaa.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** One forecast hour: temperature at [ts], precipitation (mm) for the hour starting at [ts]. */
data class FcPoint(val ts: Long, val temp: Double, val precip: Double, val symbol: String)

data class NowcastPoint(val ts: Long, val rate: Double)

data class Nowcast(val fetched: Long, val radarOk: Boolean, val points: List<NowcastPoint>)

/**
 * Forecasts from MET Norway (yr.no): Locationforecast 2.0 hourly, Nowcast 2.0 every 5 min while the
 * screen is on. Terms: identifying User-Agent, at most every 30 min per identical request, credit MET Norway.
 */
object Weather {
    private const val UA = "Lounassaa/0.1 github.com/egades14/weatherapp"
    private const val FORECAST_EVERY = 60 * 60 * 1000L
    private const val NOWCAST_EVERY = 5 * 60 * 1000L
    private const val HORIZON = 48 * 3600 * 1000L
    private val exec = Executors.newSingleThreadExecutor()
    private val fi = Locale("fi", "FI")

    @Volatile var nowcast: Nowcast? = null
    @Volatile var version = 0
    @Volatile private var lastForecastTry = 0L
    @Volatile private var expires = 0L
    @Volatile private var lastModified: String? = null
    @Volatile private var lastNowcastTry = 0L

    private fun coord(v: Double) = String.format(Locale.ROOT, "%.4f", v)

    // ---------- parsing (pure, unit tested) ----------

    fun parseForecast(json: String, now: Long): List<FcPoint> {
        val out = ArrayList<FcPoint>()
        val ts = JSONObject(json).getJSONObject("properties").getJSONArray("timeseries")
        for (i in 0 until ts.length()) {
            val o = ts.getJSONObject(i)
            val t = Instant.parse(o.getString("time")).toEpochMilli()
            if (t > now + HORIZON) break
            val data = o.getJSONObject("data")
            val temp = data.getJSONObject("instant").getJSONObject("details").optDouble("air_temperature", Double.NaN)
            val n1 = data.optJSONObject("next_1_hours") ?: continue
            if (temp.isNaN()) continue
            val precip = n1.optJSONObject("details")?.optDouble("precipitation_amount", Double.NaN) ?: Double.NaN
            val symbol = n1.optJSONObject("summary")?.optString("symbol_code", "") ?: ""
            out.add(FcPoint(t, temp, precip, symbol))
        }
        return out
    }

    fun parseNowcast(json: String, fetched: Long): Nowcast {
        val props = JSONObject(json).getJSONObject("properties")
        val coverage = props.optJSONObject("meta")?.optString("radar_coverage", "") ?: ""
        val pts = ArrayList<NowcastPoint>()
        val ts = props.getJSONArray("timeseries")
        for (i in 0 until ts.length()) {
            val o = ts.getJSONObject(i)
            val d = o.getJSONObject("data").getJSONObject("instant").getJSONObject("details")
            if (!d.has("precipitation_rate")) continue
            pts.add(NowcastPoint(Instant.parse(o.getString("time")).toEpochMilli(), d.getDouble("precipitation_rate")))
        }
        return Nowcast(fetched, coverage == "ok", pts)
    }

    fun kindOf(symbol: String): String = when {
        symbol.contains("sleet") -> "Räntäsadetta"
        symbol.contains("snow") -> "Lumisadetta"
        else -> "Sadetta"
    }

    /**
     * Short precipitation line: radar nowcast for the next 2 h if available, otherwise the hourly
     * forecast for the next 6 h. Null when nothing is coming.
     */
    fun precipText(nc: Nowcast?, fc: List<FcPoint>, now: Long, threshold: Double = 0.1): String? {
        val hhmm = SimpleDateFormat("HH.mm", fi)
        val kind = kindOf(fc.lastOrNull { it.ts <= now }?.symbol ?: fc.firstOrNull()?.symbol ?: "")
        if (nc != null && nc.radarOk && now - nc.fetched < 30 * 60 * 1000L) {
            val pts = nc.points.filter { it.ts >= now - 5 * 60 * 1000L }
            if (pts.isNotEmpty()) {
                if (pts.first().rate >= threshold) {
                    val stop = pts.firstOrNull { it.rate < threshold }
                    return if (stop != null) "$kind nyt, päättyy noin klo ${hhmm.format(Date(stop.ts))}"
                    else "$kind nyt, jatkuu ainakin 2 tuntia"
                }
                val start = pts.firstOrNull { it.rate >= threshold }
                if (start != null) return "$kind klo ${hhmm.format(Date(start.ts))} alkaen"
                return null
            }
        }
        val next = fc.firstOrNull { it.ts + 3600_000L > now && it.ts < now + 6 * 3600_000L && it.precip >= threshold }
            ?: return null
        val k = kindOf(next.symbol)
        return if (next.ts <= now) "$k tällä tunnilla" else "$k noin klo ${SimpleDateFormat("HH", fi).format(Date(next.ts))} alkaen"
    }

    /** Lowest forecast temperature for the coming night (22–08, or the rest of the current night). */
    fun nightMin(fc: List<FcPoint>, now: Long): FcPoint? {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        fun at(dayOffset: Int, h: Int): Long = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val (from, to) = when {
            hour < 8 -> now to at(0, 8)
            hour >= 22 -> now to at(1, 8)
            else -> at(0, 22) to at(1, 8)
        }
        return fc.filter { it.ts in from..to }.minByOrNull { it.temp }
    }

    // ---------- fetching ----------

    private fun get(url: String, ifModifiedSince: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Encoding", "gzip")
        if (ifModifiedSince != null) c.setRequestProperty("If-Modified-Since", ifModifiedSince)
        return c
    }

    private fun body(c: HttpURLConnection): String {
        val stream = if ("gzip".equals(c.contentEncoding, true)) java.util.zip.GZIPInputStream(c.inputStream) else c.inputStream
        return stream.bufferedReader().use { it.readText() }
    }

    fun maybeFetchForecast(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastForecastTry < 10 * 60 * 1000L || (now < expires && now - lastForecastTry < FORECAST_EVERY))) return
        lastForecastTry = now
        val c = ctx.applicationContext
        exec.execute { fetchForecast(c) }
    }

    private fun fetchForecast(ctx: Context) {
        val db = Db.get(ctx)
        try {
            val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${coord(Prefs.lat(ctx))}&lon=${coord(Prefs.lon(ctx))}"
            val conn = get(url, if (db.latestForecastFetch() > 0) lastModified else null)
            val code = conn.responseCode
            conn.getHeaderFieldDate("Expires", 0L).let { if (it > 0) expires = it }
            when (code) {
                304 -> db.logEvent("ennuste", "ei muuttunut")
                200 -> {
                    lastModified = conn.getHeaderField("Last-Modified")
                    val now = System.currentTimeMillis()
                    val pts = parseForecast(body(conn), now)
                    db.insertForecast(now, pts)
                    version++
                    db.logEvent("ennuste", "${pts.size} tuntia")
                }
                else -> db.logError("ennuste_haku", "HTTP $code")
            }
            conn.disconnect()
        } catch (e: Exception) {
            db.logError("ennuste_haku", e.javaClass.simpleName + ": " + e.message)
        }
    }

    fun maybeFetchNowcast(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastNowcastTry < NOWCAST_EVERY) return
        lastNowcastTry = now
        val c = ctx.applicationContext
        exec.execute { fetchNowcast(c) }
    }

    private fun fetchNowcast(ctx: Context) {
        try {
            val url = "https://api.met.no/weatherapi/nowcast/2.0/complete?lat=${coord(Prefs.lat(ctx))}&lon=${coord(Prefs.lon(ctx))}"
            val conn = get(url, null)
            val code = conn.responseCode
            if (code == 200) {
                val nc = parseNowcast(body(conn), System.currentTimeMillis())
                if (nowcast?.radarOk != nc.radarOk) {
                    Db.get(ctx).logEvent("tutkaennuste", if (nc.radarOk) "tutkapeitto ok" else "ei tutkapeittoa")
                }
                nowcast = nc
                version++
            } else if (code != 304) {
                Db.get(ctx).logError("tutkaennuste_haku", "HTTP $code")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Db.get(ctx).logError("tutkaennuste_haku", e.javaClass.simpleName + ": " + e.message)
        }
    }

    fun status(ctx: Context): String {
        val last = Db.get(ctx).latestForecastFetch()
        val f = if (last == 0L) "ei haettu" else "haettu " + SimpleDateFormat("d.M. HH.mm", fi).format(Date(last))
        val n = when (val nc = nowcast) {
            null -> "ei haettu"
            else -> if (nc.radarOk) "tutkapeitto ok" else "ei tutkapeittoa"
        }
        return "Ennuste (Yr): $f · tutkaennuste: $n"
    }
}
