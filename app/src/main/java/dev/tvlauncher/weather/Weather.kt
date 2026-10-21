package dev.tvlauncher.weather

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

enum class WeatherIcon { CLEAR_DAY, CLEAR_NIGHT, PARTLY_DAY, PARTLY_NIGHT, CLOUDY, FOG, RAIN, SNOW, STORM }

data class Conditions(val label: String, val icon: WeatherIcon)

enum class TempUnit(val apiName: String, val symbol: String) {
    CELSIUS("celsius", "°C"),
    FAHRENHEIT("fahrenheit", "°F");

    companion object {
        private val FAHRENHEIT_COUNTRIES = setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")

        fun forCountry(country: String) =
            if (country.uppercase() in FAHRENHEIT_COUNTRIES) FAHRENHEIT else CELSIUS
    }
}

/** A geocoded place (ZIP or city the user typed in the launcher settings). */
data class Place(val name: String, val latitude: Double, val longitude: Double)

data class CurrentWeather(val temperature: Int, val unit: TempUnit, val code: Int, val isDay: Boolean)

/** WMO weather interpretation codes (as returned by Open-Meteo) → label + icon. */
object WeatherCodes {
    fun describe(code: Int, isDay: Boolean): Conditions? = when (code) {
        0 -> if (isDay) Conditions("Sunny", WeatherIcon.CLEAR_DAY) else Conditions("Clear", WeatherIcon.CLEAR_NIGHT)
        1 -> if (isDay) Conditions("Mostly sunny", WeatherIcon.PARTLY_DAY) else Conditions("Mostly clear", WeatherIcon.PARTLY_NIGHT)
        2 -> Conditions("Partly cloudy", if (isDay) WeatherIcon.PARTLY_DAY else WeatherIcon.PARTLY_NIGHT)
        3 -> Conditions("Cloudy", WeatherIcon.CLOUDY)
        45, 48 -> Conditions("Foggy", WeatherIcon.FOG)
        51, 53, 55 -> Conditions("Drizzle", WeatherIcon.RAIN)
        56, 57 -> Conditions("Freezing drizzle", WeatherIcon.RAIN)
        61 -> Conditions("Light rain", WeatherIcon.RAIN)
        63 -> Conditions("Rain", WeatherIcon.RAIN)
        65 -> Conditions("Heavy rain", WeatherIcon.RAIN)
        66, 67 -> Conditions("Freezing rain", WeatherIcon.RAIN)
        71 -> Conditions("Light snow", WeatherIcon.SNOW)
        73 -> Conditions("Snow", WeatherIcon.SNOW)
        75 -> Conditions("Heavy snow", WeatherIcon.SNOW)
        77 -> Conditions("Snow grains", WeatherIcon.SNOW)
        80 -> Conditions("Showers", WeatherIcon.RAIN)
        81 -> Conditions("Rain showers", WeatherIcon.RAIN)
        82 -> Conditions("Heavy showers", WeatherIcon.RAIN)
        85, 86 -> Conditions("Snow showers", WeatherIcon.SNOW)
        95 -> Conditions("Thunderstorms", WeatherIcon.STORM)
        96, 99 -> Conditions("Storms with hail", WeatherIcon.STORM)
        else -> null
    }
}

/** Open-Meteo current-conditions API (free, no key). */
object OpenMeteo {
    /** Coordinates are rounded to 2 decimals (~1 km): the forecast doesn't need more. */
    fun url(latitude: Double, longitude: Double, unit: TempUnit): String =
        String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                "&current=temperature_2m,weather_code,is_day&temperature_unit=%s",
            latitude,
            longitude,
            unit.apiName,
        )

    /** Place search; accepts postal codes. Scoped to [country] (ISO code) when known. */
    fun geocodeUrl(query: String, country: String): String {
        val name = URLEncoder.encode(query.trim(), "UTF-8")
        val scope = if (country.isNotBlank()) "&countryCode=${country.uppercase()}" else ""
        return "https://geocoding-api.open-meteo.com/v1/search?name=$name&count=1&language=en&format=json$scope"
    }

    fun parsePlace(json: String): Place? {
        val first = JSONObject(json).optJSONArray("results")?.optJSONObject(0) ?: return null
        return Place(first.getString("name"), first.getDouble("latitude"), first.getDouble("longitude"))
    }

    fun parse(json: String, unit: TempUnit): CurrentWeather {
        val current = JSONObject(json).getJSONObject("current")
        return CurrentWeather(
            temperature = current.getDouble("temperature_2m").roundToInt(),
            unit = unit,
            code = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
        )
    }
}
