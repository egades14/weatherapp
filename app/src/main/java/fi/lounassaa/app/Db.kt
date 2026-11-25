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
    SQLiteOpenHelper(ctx.applicationContext, "lounassaa.db", null, 1) {

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

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
    fun recentEvents(n: Int): List<String> {
        val fmt = SimpleDateFormat("d.M. HH:mm:ss", Locale("fi", "FI"))
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT ts, kind, info FROM events ORDER BY ts DESC, rowid DESC LIMIT ?",
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
