package dev.tvlauncher.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherTest {
    @Test
    fun `describes WMO codes with day and night variants`() {
        assertEquals(Conditions("Sunny", WeatherIcon.CLEAR_DAY), WeatherCodes.describe(0, isDay = true))
        assertEquals(Conditions("Clear", WeatherIcon.CLEAR_NIGHT), WeatherCodes.describe(0, isDay = false))
        assertEquals(Conditions("Partly cloudy", WeatherIcon.PARTLY_NIGHT), WeatherCodes.describe(2, isDay = false))
        assertEquals(Conditions("Cloudy", WeatherIcon.CLOUDY), WeatherCodes.describe(3, isDay = true))
        assertEquals(Conditions("Foggy", WeatherIcon.FOG), WeatherCodes.describe(48, isDay = true))
        assertEquals(Conditions("Light rain", WeatherIcon.RAIN), WeatherCodes.describe(61, isDay = true))
        assertEquals(Conditions("Heavy snow", WeatherIcon.SNOW), WeatherCodes.describe(75, isDay = true))
        assertEquals(Conditions("Thunderstorms", WeatherIcon.STORM), WeatherCodes.describe(95, isDay = false))
        assertNull(WeatherCodes.describe(42, isDay = true))
    }

    @Test
    fun `builds the request with coarse coordinates and the right unit`() {
        assertEquals(
            "https://api.open-meteo.com/v1/forecast?latitude=43.07&longitude=-89.40" +
                "&current=temperature_2m,weather_code,is_day&temperature_unit=fahrenheit",
            OpenMeteo.url(43.07312, -89.40123, TempUnit.FAHRENHEIT),
        )
    }

    @Test
    fun `parses current conditions`() {
        val json = """{"current":{"time":"2026-10-17T15:00","temperature_2m":64.6,"weather_code":2,"is_day":1}}"""
        assertEquals(CurrentWeather(65, TempUnit.FAHRENHEIT, 2, true), OpenMeteo.parse(json, TempUnit.FAHRENHEIT))
    }

    @Test
    fun `picks the temperature unit by country`() {
        assertEquals(TempUnit.FAHRENHEIT, TempUnit.forCountry("US"))
        assertEquals(TempUnit.CELSIUS, TempUnit.forCountry("GB"))
        assertEquals(TempUnit.CELSIUS, TempUnit.forCountry(""))
    }

    @Test
    fun `looks up a ZIP or city, scoped to the TV's country`() {
        assertEquals(
            "https://geocoding-api.open-meteo.com/v1/search?name=94110&count=1&language=en&format=json&countryCode=US",
            OpenMeteo.geocodeUrl(" 94110 ", "US"),
        )
        assertEquals(
            "https://geocoding-api.open-meteo.com/v1/search?name=San+Francisco&count=1&language=en&format=json",
            OpenMeteo.geocodeUrl("San Francisco", ""),
        )
    }

    @Test
    fun `parses the first place match or none`() {
        val json = """{"results":[{"name":"New York","latitude":40.71427,"longitude":-74.00597,"admin1":"New York"}]}"""
        assertEquals(Place("New York", 40.71427, -74.00597), OpenMeteo.parsePlace(json))
        assertNull(OpenMeteo.parsePlace("""{"generationtime_ms":0.5}"""))
    }
}
