package fi.lounassaa.app

import android.content.Context
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class Seen(val ts: Long, val r: RuuviReading, val rssi: Int)

/** Latest advertisement per tag, held in memory (service and UI share the process). */
object Live {
    val latest = ConcurrentHashMap<String, Seen>()
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("lounassaa", Context.MODE_PRIVATE)
    fun outdoor(c: Context): String? = sp(c).getString("outdoor", null)
    fun indoor(c: Context): String? = sp(c).getString("indoor", null)
    fun setOutdoor(c: Context, mac: String?) = sp(c).edit().putString("outdoor", mac).apply()
    fun setIndoor(c: Context, mac: String?) = sp(c).edit().putString("indoor", mac).apply()
    fun autostart(c: Context): Boolean = sp(c).getBoolean("autostart", false)
    fun setAutostart(c: Context, v: Boolean) = sp(c).edit().putBoolean("autostart", v).apply()
}

object Fmt {
    private val fi = Locale("fi", "FI")
    fun temp(v: Double): String = String.format(fi, "%.1f °C", v)
    fun hum(v: Double): String = String.format(fi, "%.0f %%", v)
    fun pres(v: Double): String = String.format(fi, "%.0f hPa", v)
    fun volt(v: Double): String = String.format(fi, "%.2f V", v)
    fun age(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 90 -> "$s s sitten"
            s < 5400 -> "${s / 60} min sitten"
            else -> "${s / 3600} h sitten"
        }
    }
}
