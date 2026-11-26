package fi.lounassaa.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WeatherTest {
    private val t0 = Instant.parse("2026-11-26T12:00:00Z").toEpochMilli()

    private val forecastJson = """
        {"type":"Feature","properties":{"meta":{"updated_at":"2026-11-26T11:00:00Z"},"timeseries":[
          {"time":"2026-11-26T12:00:00Z","data":{"instant":{"details":{"air_temperature":-8.4}},
            "next_1_hours":{"summary":{"symbol_code":"cloudy"},"details":{"precipitation_amount":0.0}}}},
          {"time":"2026-11-26T13:00:00Z","data":{"instant":{"details":{"air_temperature":-9.1}},
            "next_1_hours":{"summary":{"symbol_code":"lightsnow"},"details":{"precipitation_amount":0.4}}}},
          {"time":"2026-11-29T12:00:00Z","data":{"instant":{"details":{"air_temperature":-15.0}},
            "next_6_hours":{"summary":{"symbol_code":"clearsky_day"},"details":{"precipitation_amount":0.0}}}}
        ]}}
    """.trimIndent()

    private val nowcastJson = """
        {"properties":{"meta":{"radar_coverage":"ok"},"timeseries":[
          {"time":"2026-11-26T12:00:00Z","data":{"instant":{"details":{"air_temperature":-8.0,"precipitation_rate":0.0}}}},
          {"time":"2026-11-26T12:05:00Z","data":{"instant":{"details":{"precipitation_rate":0.0}}}},
          {"time":"2026-11-26T12:10:00Z","data":{"instant":{"details":{"precipitation_rate":0.6}}}},
          {"time":"2026-11-26T12:15:00Z","data":{"instant":{"details":{"precipitation_rate":0.0}}}}
        ]}}
    """.trimIndent()

    @Test
    fun parsesHourlyForecastWithinHorizon() {
        val pts = Weather.parseForecast(forecastJson, t0)
        assertEquals(2, pts.size)
        assertEquals(-8.4, pts[0].temp, 1e-9)
        assertEquals(0.4, pts[1].precip, 1e-9)
        assertEquals("lightsnow", pts[1].symbol)
    }

    @Test
    fun parsesNowcast() {
        val nc = Weather.parseNowcast(nowcastJson, t0)
        assertTrue(nc.radarOk)
        assertEquals(4, nc.points.size)
        assertEquals(0.6, nc.points[2].rate, 1e-9)
    }

    @Test
    fun precipTextFromRadarThenForecast() {
        val fc = Weather.parseForecast(forecastJson, t0)
        val nc = Weather.parseNowcast(nowcastJson, t0)
        val text = Weather.precipText(nc, fc, t0)!!
        assertTrue(text, text.startsWith("Sadetta klo") && text.endsWith("alkaen"))

        val noRadar = nc.copy(radarOk = false)
        val fromFc = Weather.precipText(noRadar, fc, t0)!!
        assertTrue(fromFc, fromFc.startsWith("Lumisadetta noin klo"))

        val dry = fc.map { it.copy(precip = 0.0) }
        assertNull(Weather.precipText(null, dry, t0))
    }

    @Test
    fun kinds() {
        assertEquals("Räntäsadetta", Weather.kindOf("lightsleetshowers_night"))
        assertEquals("Lumisadetta", Weather.kindOf("heavysnow"))
        assertEquals("Sadetta", Weather.kindOf("rain"))
        assertFalse(Weather.kindOf("") == "Lumisadetta")
    }
}

class PrecipKindTest {
    @org.junit.Test
    fun fromSymbolThenTemperature() {
        org.junit.Assert.assertEquals(PrecipKind.SNOW, Weather.kindFor("lightsnowshowers_night", 3.0))
        org.junit.Assert.assertEquals(PrecipKind.SLEET, Weather.kindFor("sleet", -5.0))
        org.junit.Assert.assertEquals(PrecipKind.RAIN, Weather.kindFor("lightrain", -0.5))
        org.junit.Assert.assertEquals(PrecipKind.SNOW, Weather.kindFor("cloudy", -8.0))
        org.junit.Assert.assertEquals(PrecipKind.SLEET, Weather.kindFor("cloudy", 0.5))
        org.junit.Assert.assertEquals(PrecipKind.RAIN, Weather.kindFor("cloudy", 4.0))
    }
}
