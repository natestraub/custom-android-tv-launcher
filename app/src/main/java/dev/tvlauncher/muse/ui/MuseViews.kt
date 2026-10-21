package dev.tvlauncher.muse.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** The Muse dialogs' shared look: a dark rounded panel, white text, remote-friendly pill buttons. */
class MuseViews(private val context: Context) {
    private val density = context.resources.displayMetrics.density

    fun dp(v: Int) = (v * density).roundToInt()

    fun panel() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 24 * density
            setColor(0xF21C1D21.toInt())
            setStroke(dp(1), 0x26FFFFFF)
        }
    }

    /** Solid white when focused, faint otherwise. */
    fun pill(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(22), dp(11), dp(22), dp(11))
        focusable(this) { focused ->
            setTextColor(if (focused) 0xFF111111.toInt() else Color.WHITE)
        }
        setOnClickListener { onClick() }
    }

    /** A round icon button in the pill style. */
    fun iconPill(icon: Int, description: String, onClick: () -> Unit) = FrameLayout(context).apply {
        val image = ImageView(context).apply { setImageResource(icon) }
        addView(image, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        contentDescription = description
        focusable(this) { focused -> image.setColorFilter(if (focused) 0xFF111111.toInt() else Color.WHITE) }
        setOnClickListener { onClick() }
    }

    fun text(value: String, sp: Float, bold: Boolean = false, alpha: Float = 1f, mono: Boolean = false) = TextView(context).apply {
        text = value
        textSize = sp
        setTextColor(Color.WHITE)
        this.alpha = alpha
        typeface = when {
            mono -> Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        setLineSpacing(0f, 1.15f)
    }

    private fun focusable(view: View, style: (Boolean) -> Unit) {
        val bg = GradientDrawable().apply { cornerRadius = 999f }
        view.background = bg
        view.isFocusable = true
        view.isClickable = true
        fun apply(focused: Boolean) {
            bg.setColor(if (focused) Color.WHITE else 0x26FFFFFF)
            style(focused)
        }
        apply(false)
        view.setOnFocusChangeListener { _, has -> apply(has) }
    }
}
