package fi.lounassaa.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightTest {
    private val start = 23 * 60
    private val end = 6 * 60 + 30

    @Test
    fun overMidnight() {
        assertTrue(Night.isNightMinute(23 * 60, start, end))
        assertTrue(Night.isNightMinute(2 * 60, start, end))
        assertTrue(Night.isNightMinute(6 * 60 + 29, start, end))
        assertFalse(Night.isNightMinute(6 * 60 + 30, start, end))
        assertFalse(Night.isNightMinute(12 * 60, start, end))
        assertFalse(Night.isNightMinute(22 * 60 + 59, start, end))
    }

    @Test
    fun sameDayAndEmpty() {
        assertTrue(Night.isNightMinute(60, 30, 120))
        assertFalse(Night.isNightMinute(150, 30, 120))
        assertFalse(Night.isNightMinute(100, 100, 100))
    }
}
