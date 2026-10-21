package dev.tvlauncher.weather

import android.content.Context
import android.os.Handler
import android.util.Log
import dev.tvlauncher.Http
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Current conditions for the top bar. The place is a ZIP/city typed once in
 * the launcher settings (the TV has no location fix of its own), geocoded by
 * Open-Meteo. The forecast is fetched every 30 min while the launcher is in
 * front; the last reading is cached so the top bar fills instantly on cold start.
 */
class WeatherController(
    context: Context,
    private val io: Executor,
    private val main: Handler,
    private val onUpdate: (CurrentWeather?) -> Unit,
) {
    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val country = Locale.getDefault().country
    private val unit = TempUnit.forCountry(country)
    private val tick = Runnable { refresh() }
    private var running = false

    /** What the user typed, for pre-filling the settings dialog. */
    val placeQuery: String get() = prefs.getString(KEY_QUERY, "").orEmpty()

    fun start() {
        if (running) return
        running = true
        cached()?.let(onUpdate)
        val age = System.currentTimeMillis() - prefs.getLong(KEY_FETCHED_AT, 0L)
        if (age >= REFRESH_MS) refresh() else main.postDelayed(tick, REFRESH_MS - age)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
    }

    /**
     * Geocode [query] (ZIP or city) and switch the weather to it. Blank clears
     * it. [onResult] gets the matched place name, or null when nothing matched.
     */
    fun setPlace(query: String, onResult: (String?) -> Unit) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            prefs.edit().clear().apply()
            onUpdate(null)
            onResult(null)
            return
        }
        io.execute {
            val place = try {
                OpenMeteo.parsePlace(Http.getText(OpenMeteo.geocodeUrl(trimmed, country)))
            } catch (e: Exception) {
                Log.w(TAG, "place lookup failed", e)
                null
            }
            if (place != null) {
                prefs.edit()
                    .putString(KEY_QUERY, trimmed)
                    .putString(KEY_LAT, place.latitude.toString())
                    .putString(KEY_LON, place.longitude.toString())
                    .remove(KEY_FETCHED_AT)
                    .apply()
            }
            main.post {
                onResult(place?.name)
                if (place != null) refresh()
            }
        }
    }

    private fun refresh() {
        main.removeCallbacks(tick)
        if (!running) return
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return
        val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return
        io.execute {
            try {
                val weather = OpenMeteo.parse(Http.getText(OpenMeteo.url(lat, lon, unit)), unit)
                save(weather)
                main.post { if (running) onUpdate(weather) }
            } catch (e: Exception) {
                Log.w(TAG, "weather unavailable", e)
            }
        }
        main.postDelayed(tick, REFRESH_MS)
    }

    private fun save(w: CurrentWeather) {
        prefs.edit()
            .putInt(KEY_TEMP, w.temperature)
            .putString(KEY_UNIT, w.unit.name)
            .putInt(KEY_CODE, w.code)
            .putBoolean(KEY_DAY, w.isDay)
            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun cached(): CurrentWeather? {
        val at = prefs.getLong(KEY_FETCHED_AT, 0L)
        if (at == 0L || System.currentTimeMillis() - at > STALE_MS) return null
        val savedUnit = runCatching { TempUnit.valueOf(prefs.getString(KEY_UNIT, "")!!) }.getOrNull() ?: return null
        if (savedUnit != unit) return null
        return CurrentWeather(prefs.getInt(KEY_TEMP, 0), savedUnit, prefs.getInt(KEY_CODE, 0), prefs.getBoolean(KEY_DAY, true))
    }

    companion object {
        private const val TAG = "Weather"
        private const val REFRESH_MS = 30 * 60_000L
        private const val STALE_MS = 3 * 60 * 60_000L
        private const val KEY_QUERY = "query"
        private const val KEY_LAT = "lat"
        private const val KEY_LON = "lon"
        private const val KEY_TEMP = "temp"
        private const val KEY_UNIT = "unit"
        private const val KEY_CODE = "code"
        private const val KEY_DAY = "is_day"
        private const val KEY_FETCHED_AT = "fetched_at"
    }
}
