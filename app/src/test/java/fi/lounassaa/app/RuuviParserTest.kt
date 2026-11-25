package fi.lounassaa.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuuviParserTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun validSampleFromRuuviSpec() {
        val r = RuuviParser.parseV5(hex("0512FC5394C37C0004FFFC040CAC364200CDCBB8334C884F"))!!
        assertEquals(24.3, r.tempC, 0.0001)
        assertEquals(53.49, r.humidity, 0.0001)
        assertEquals(1000.44, r.pressureHpa, 0.0001)
        assertEquals(2.977, r.voltage, 0.0001)
        assertEquals(4, r.txPowerDbm)
        assertEquals(66, r.movement)
        assertEquals(205, r.seq)
    }

    @Test
    fun maximumValues() {
        val r = RuuviParser.parseV5(hex("057FFFFFFEFFFE0000000000000000000000000000000000"))
        // power field 0x0000 is valid; temperature 0x7FFF = 163.835
        assertEquals(163.835, r!!.tempC, 0.0001)
    }

    @Test
    fun minimumValues() {
        val r = RuuviParser.parseV5(hex("058001000000000000000000000000000000000000000000"))!!
        assertEquals(-163.835, r.tempC, 0.0001)
    }

    @Test
    fun invalidMarkersAndWrongFormat() {
        assertNull(RuuviParser.parseV5(hex("058000FFFFFFFF000000000000000000000000000000000000")))
        assertNull(RuuviParser.parseV5(hex("0312FC5394C37C0004FFFC040CAC364200CDCBB8334C884F")))
        assertNull(RuuviParser.parseV5(ByteArray(5)))
    }
}
