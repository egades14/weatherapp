package fi.lounassaa.app

data class RuuviReading(
    val tempC: Double,
    val humidity: Double,
    val pressureHpa: Double,
    val voltage: Double,
    val txPowerDbm: Int,
    val movement: Int,
    val seq: Int,
)

/** RuuviTag data format 5 (RAWv2). Input: manufacturer data without the company id. */
object RuuviParser {
    const val MANUFACTURER_ID = 0x0499

    fun parseV5(d: ByteArray): RuuviReading? {
        if (d.size < 18 || d[0].toInt() != 5) return null
        fun s16(i: Int): Int = (d[i].toInt() shl 8) or (d[i + 1].toInt() and 0xFF)
        fun u16(i: Int): Int = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

        val t = s16(1)
        val h = u16(3)
        val p = u16(5)
        val power = u16(13)
        if (t == -32768 || h == 0xFFFF || p == 0xFFFF || power == 0xFFFF) return null

        return RuuviReading(
            tempC = t * 0.005,
            humidity = h * 0.0025,
            pressureHpa = (p + 50000) / 100.0,
            voltage = ((power ushr 5) + 1600) / 1000.0,
            txPowerDbm = (power and 0x1F) * 2 - 40,
            movement = d[15].toInt() and 0xFF,
            seq = u16(16),
        )
    }
}

/** Rejects values that cannot be real, so garbage never reaches the database. */
object Plausible {
    fun check(r: RuuviReading): String? = when {
        r.tempC < -60.0 || r.tempC > 60.0 -> "lämpötila ${r.tempC}"
        r.humidity < 0.0 || r.humidity > 100.0 -> "kosteus ${r.humidity}"
        r.pressureHpa < 850.0 || r.pressureHpa > 1090.0 -> "ilmanpaine ${r.pressureHpa}"
        r.voltage < 1.6 || r.voltage > 3.7 -> "jännite ${r.voltage}"
        else -> null
    }
}
