package dev.tvlauncher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import dev.tvlauncher.R
import dev.tvlauncher.weather.CurrentWeather
import dev.tvlauncher.weather.WeatherCodes
import dev.tvlauncher.weather.WeatherIcon
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Top right, two blocks: weather (icon + temp / condition) and clock (time / date). Big
 * lines share one style, small lines another. TextClock ticks itself (incl. midnight).
 */
class StatusBlock(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val bigFace = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val smallFace = Typeface.create("sans-serif", Typeface.NORMAL)
    private val weatherIcon = ImageView(context)
    private val weatherTemp = TextView(context).big()
    private val weatherLabel = TextView(context).small()

    // Icon beside a temp/condition column; the condition starts where the temp starts.
    private val weatherBlock = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
        addView(weatherIcon, LinearLayout.LayoutParams(28.dp, 28.dp).apply {
            rightMargin = 10.dp
            topMargin = (weatherTemp.lineHeight - 28.dp) / 2 // centered on the temp line
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(weatherTemp)
            addView(weatherLabel)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(weatherBlock, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = 48.dp })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(TextClock(context).apply {
                format12Hour = "h:mm"
                format24Hour = "HH:mm"
            }.big())
            val datePattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd")
            addView(TextClock(context).apply {
                format12Hour = datePattern
                format24Hour = datePattern
            }.small())
        })
    }

    fun showWeather(w: CurrentWeather?) {
        if (w == null) {
            weatherBlock.visibility = View.GONE
            return
        }
        val conditions = WeatherCodes.describe(w.code, w.isDay)
        weatherTemp.text = "${w.temperature}${w.unit.symbol}"
        weatherLabel.text = conditions?.label.orEmpty()
        weatherIcon.setImageResource(
            when (conditions?.icon ?: WeatherIcon.CLOUDY) {
                WeatherIcon.CLEAR_DAY -> R.drawable.ic_weather_clear_day
                WeatherIcon.CLEAR_NIGHT -> R.drawable.ic_weather_clear_night
                WeatherIcon.PARTLY_DAY -> R.drawable.ic_weather_partly_day
                WeatherIcon.PARTLY_NIGHT -> R.drawable.ic_weather_partly_night
                WeatherIcon.CLOUDY -> R.drawable.ic_weather_cloudy
                WeatherIcon.FOG -> R.drawable.ic_weather_fog
                WeatherIcon.RAIN -> R.drawable.ic_weather_rain
                WeatherIcon.SNOW -> R.drawable.ic_weather_snow
                WeatherIcon.STORM -> R.drawable.ic_weather_storm
            },
        )
        weatherBlock.visibility = View.VISIBLE
    }

    private fun <T : TextView> T.big() = apply {
        setTextColor(Color.WHITE)
        textSize = 26f
        typeface = bigFace
        setShadowLayer(8f, 0f, 1f, 0x66000000)
    }

    private fun <T : TextView> T.small() = apply {
        setTextColor(0xCCFFFFFF.toInt())
        textSize = 13f
        typeface = smallFace
        setShadowLayer(6f, 0f, 1f, 0x66000000)
    }

    private val Int.dp: Int get() = (this * density).roundToInt()

    private companion object {
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
