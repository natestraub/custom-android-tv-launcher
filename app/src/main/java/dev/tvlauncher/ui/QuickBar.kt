package dev.tvlauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import dev.tvlauncher.R
import kotlin.math.roundToInt

/**
 * The faint row of round buttons above the app row: the search spyglass, Muse,
 * then any pinned apps as logo-only icons. Dimmed until focused.
 */
class QuickBar(
    context: Context,
    private val onSearch: () -> Unit,
    private val onMuse: () -> Unit,
    private val onLaunch: (String) -> Unit,
    private val onLongPress: (String) -> Unit,
    /** DPAD-down from any button (back to the app row). */
    private val onDown: () -> Boolean,
    /** DPAD-up from any button (to the row above, when there is one). */
    private val onUp: () -> Boolean = { false },
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private var upId = NO_ID

    val search: ImageView = button(padding = 10).apply {
        id = View.generateViewId()
        setImageResource(R.drawable.ic_search)
        contentDescription = context.getString(R.string.search)
        setOnClickListener { onSearch() }
    }

    // The Muse mark is thin and wide, so it gets less padding to read as large as its neighbours.
    val muse: ImageView = button(padding = 6).apply {
        setImageResource(R.drawable.logo_muse)
        contentDescription = context.getString(R.string.muse)
        setOnClickListener { onMuse() }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(search, buttonParams())
        addView(muse, buttonParams().apply { leftMargin = dp(6) })
    }

    fun setUpFocus(id: Int) {
        upId = id
        for (i in 0 until childCount) getChildAt(i).nextFocusUpId = id
    }

    /** Replace the pinned buttons (everything after the fixed search + Muse buttons). */
    fun setPinned(icons: List<Pair<String, Bitmap>>, labels: Map<String, String>) {
        val focusedPkg = (findFocus()?.tag as? String)
        while (childCount > FIXED_BUTTONS) removeViewAt(FIXED_BUTTONS)
        for ((pkg, bitmap) in icons) {
            val b = button(padding = 9).apply {
                tag = pkg
                setImageBitmap(bitmap)
                contentDescription = labels[pkg] ?: pkg
                setOnClickListener { onLaunch(pkg) }
                setOnLongClickListener {
                    onLongPress(pkg)
                    true
                }
            }
            addView(b, buttonParams().apply { leftMargin = dp(6) })
            if (pkg == focusedPkg) b.requestFocus()
        }
        setUpFocus(upId)
    }

    private fun button(padding: Int) = ImageView(context).apply {
        setBackgroundResource(R.drawable.icon_button_bg)
        val pad = dp(padding)
        setPadding(pad, pad, pad, pad)
        isFocusable = true
        alpha = IDLE_ALPHA
        setOnFocusChangeListener { v, hasFocus -> v.alpha = if (hasFocus) 1f else IDLE_ALPHA }
        setOnKeyListener { _, keyCode, event ->
            event.action == KeyEvent.ACTION_DOWN && when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> onDown()
                KeyEvent.KEYCODE_DPAD_UP -> onUp()
                else -> false
            }
        }
    }

    private fun buttonParams() = LayoutParams(dp(44), dp(44))

    private fun dp(v: Int) = (v * density).roundToInt()

    companion object {
        private const val IDLE_ALPHA = 0.6f
        private const val FIXED_BUTTONS = 2
    }
}
