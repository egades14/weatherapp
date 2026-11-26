package fi.lounassaa.app

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StoredReading(
    val mac: String,
    val ts: Long,
    val tempC: Double,
    val humidity: Double,
    val pressureHpa: Double,
    val voltage: Double,
    val rssi: Int,
)

class Db private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "lounassaa.db", null, 2) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(c).also { inst = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE readings(" +
                "ts INTEGER NOT NULL, mac TEXT NOT NULL, temp REAL, hum REAL, pres REAL, " +
                "volt REAL, rssi INTEGER, seq INTEGER, PRIMARY KEY(ts, mac))"
        )
        db.execSQL("CREATE TABLE events(ts INTEGER NOT NULL, kind TEXT NOT NULL, info TEXT)")
        createForecast(db)
    }

    private fun createForecast(db: SQLiteDatabase) {
        // One row per forecast hour per fetch, kept permanently (yesterday's forecast, accuracy comparison).
        db.execSQL(
            "CREATE TABLE forecast(fetched INTEGER NOT NULL, ts INTEGER NOT NULL, temp REAL, " +
                "precip REAL, symbol TEXT, PRIMARY KEY(fetched, ts))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createForecast(db)
    }

    @Synchronized
    fun insertForecast(fetched: Long, points: List<FcPoint>) {
        val w = writableDatabase
        w.beginTransaction()
        try {
            for (p in points) {
                val v = ContentValues().apply {
                    put("fetched", fetched)
                    put("ts", p.ts)
                    put("temp", p.temp)
                    if (!p.precip.isNaN()) put("precip", p.precip)
                    put("symbol", p.symbol)
                }
                w.insertWithOnConflict("forecast", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    @Synchronized
    fun latestForecastFetch(): Long = readableDatabase.rawQuery("SELECT MAX(fetched) FROM forecast", null).use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
    }

    /** The fetch closest to [ts], if one exists within [tolerance] ms. */
    @Synchronized
    fun forecastFetchNear(ts: Long, tolerance: Long): Long = readableDatabase.rawQuery(
        "SELECT fetched FROM forecast WHERE fetched BETWEEN ? AND ? GROUP BY fetched ORDER BY ABS(fetched - ?) LIMIT 1",
        arrayOf((ts - tolerance).toString(), (ts + tolerance).toString(), ts.toString())
    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    @Synchronized
    fun forecast(fetched: Long, from: Long, to: Long): List<FcPoint> {
        val out = ArrayList<FcPoint>()
        readableDatabase.rawQuery(
            "SELECT ts, temp, precip, symbol FROM forecast WHERE fetched = ? AND ts BETWEEN ? AND ? ORDER BY ts",
            arrayOf(fetched.toString(), from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    FcPoint(
                        c.getLong(0), c.getDouble(1),
                        if (c.isNull(2)) Double.NaN else c.getDouble(2), c.getString(3) ?: ""
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun temps(mac: String, from: Long, to: Long): List<Pair<Long, Double>> {
        val out = ArrayList<Pair<Long, Double>>()
        readableDatabase.rawQuery(
            "SELECT ts, temp FROM readings WHERE mac = ? AND ts BETWEEN ? AND ? ORDER BY ts",
            arrayOf(mac, from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0) to c.getDouble(1)) }
        return out
    }

    @Synchronized
    fun insertReading(ts: Long, mac: String, r: RuuviReading, rssi: Int) {
        val v = ContentValues().apply {
            put("ts", ts)
            put("mac", mac)
            put("temp", r.tempC)
            put("hum", r.humidity)
            put("pres", r.pressureHpa)
            put("volt", r.voltage)
            put("rssi", rssi)
            put("seq", r.seq)
        }
        writableDatabase.insertWithOnConflict("readings", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun logEvent(kind: String, info: String = "") {
        val v = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("kind", kind)
            put("info", info)
        }
        writableDatabase.insert("events", null, v)
    }

    /** Errors are events whose kind starts with VIRHE_. */
    fun logError(kind: String, info: String = "") = logEvent("VIRHE_$kind", info)

    @Synchronized
    fun errorCountSince(sinceTs: Long): Long = DatabaseUtils.queryNumEntries(
        readableDatabase, "events", "kind LIKE 'VIRHE\\_%' ESCAPE '\\' AND ts >= ?", arrayOf(sinceTs.toString())
    )

    @Synchronized
    fun recentErrors(n: Int): List<String> = queryEvents("WHERE kind LIKE 'VIRHE\\_%' ESCAPE '\\'", n)

    @Synchronized
    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "readings")

    @Synchronized
    fun latestPerTag(): List<StoredReading> {
        val out = ArrayList<StoredReading>()
        readableDatabase.rawQuery(
            "SELECT mac, ts, temp, hum, pres, volt, rssi FROM readings r " +
                "WHERE ts = (SELECT MAX(ts) FROM readings WHERE mac = r.mac) ORDER BY mac",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    StoredReading(
                        c.getString(0), c.getLong(1), c.getDouble(2), c.getDouble(3),
                        c.getDouble(4), c.getDouble(5), c.getInt(6)
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun recentEvents(n: Int): List<String> = queryEvents("", n)

    private fun queryEvents(where: String, n: Int): List<String> {
        val fmt = SimpleDateFormat("d.M. HH:mm:ss", Locale("fi", "FI"))
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT ts, kind, info FROM events $where ORDER BY ts DESC, rowid DESC LIMIT ?",
            arrayOf(n.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val info = c.getString(2) ?: ""
                out.add(fmt.format(Date(c.getLong(0))) + "  " + c.getString(1) + if (info.isEmpty()) "" else "  $info")
            }
        }
        return out
    }
}
