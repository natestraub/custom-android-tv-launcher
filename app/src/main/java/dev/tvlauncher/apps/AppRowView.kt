package dev.tvlauncher.apps

import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import dev.tvlauncher.ui.FrameAnimator
import dev.tvlauncher.ui.ScrollMath

/**
 * Single horizontal row of app tiles on a translucent rounded panel.
 *
 * Scrolling moves one inner strip via translationX (a RenderNode property):
 * no re-layout and no re-recording of tile display lists while scrolling.
 * Move mode (long-press → Move) slides the held tile left/right among its
 * neighbours; only the two swapped tiles animate.
 */
class AppRowView(
    context: Context,
    private val tileW: Int,
    private val tileH: Int,
    private val gap: Int,
    private val padH: Int,
    private val padV: Int,
    private val radius: Float,
) : ViewGroup(context) {
    var onLaunch: ((AppEntry) -> Unit)? = null
    var onLongPress: ((AppEntry) -> Unit)? = null

    /** Called with the row's package order when a move finishes. */
    var onOrderChanged: ((List<String>) -> Unit)? = null

    /** DPAD-down from a tile (to the row below, when there is one). */
    var onDown: (() -> Boolean)? = null

    /** View id DPAD-up goes to from any tile. */
    var upFocusId: Int = NO_ID

    private val strip = Strip(context)
    private var lastFocused: View? = null
    private var moving: AppTileView? = null
    private var scrollFrom = 0f
    private var scrollTo = 0f
    private val scrollAnim = FrameAnimator(SCROLL_MS) { f ->
        strip.translationX = scrollFrom + (scrollTo - scrollFrom) * f
    }
    private val slideTiles = mutableListOf<Pair<View, Float>>()
    private val slideAnim = FrameAnimator(SLIDE_MS) { f ->
        for ((tile, from) in slideTiles) tile.translationX = from * (1f - f)
    }

    init {
        background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(0x24FFFFFF)
            setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), 0x33FFFFFF)
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
        clipChildren = false // focused tile scales past the strip; the outline still bounds it
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(strip)
    }

    fun setApps(apps: List<AppEntry>) {
        finishMove(notify = false)
        val focused = findFocus() as? AppTileView
        val focusedIndex = focused?.let { strip.indexOfChild(it) } ?: -1
        val focusedPackage = focused?.app?.packageName
        val rememberedPackage = (lastFocused as? AppTileView)?.app?.packageName
        lastFocused = null
        strip.removeAllViews()
        for (app in apps) {
            strip.addView(
                AppTileView(context, app, radius * 0.6f).apply {
                    nextFocusUpId = upFocusId
                    setOnClickListener { onLaunch?.invoke(app) }
                    setOnLongClickListener {
                        onLongPress?.invoke(app)
                        true
                    }
                },
            )
        }
        val restore = strip.tiles().firstOrNull { it.app.packageName == (focusedPackage ?: rememberedPackage) }
            ?: focusedIndex.takeIf { it >= 0 }?.let { strip.getChildAt(it.coerceAtMost(strip.childCount - 1)) }
        lastFocused = restore
        if (focused != null) (restore ?: strip.getChildAt(0))?.requestFocus()
    }

    /** Back to the first app, scrolled to the start (Home pressed while home). */
    fun reset() {
        finishMove(notify = true)
        scrollAnim.cancel()
        strip.translationX = 0f
        lastFocused = null
        strip.getChildAt(0)?.requestFocus()
    }

    /** Pick up [packageName]'s tile: left/right moves it, OK/Back puts it down. */
    fun startMove(packageName: String) {
        val tile = strip.tiles().firstOrNull { it.app.packageName == packageName } ?: return
        finishMove(notify = true)
        moving = tile
        tile.moving = true
        tile.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val tile = moving ?: run {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && onDown?.invoke() == true) {
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    shift(tile, if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            -> if (event.action == KeyEvent.ACTION_UP) finishMove(notify = true)
        }
        return true // everything else is swallowed while a tile is held
    }

    private fun shift(tile: AppTileView, delta: Int) {
        val from = strip.indexOfChild(tile)
        val to = from + delta
        if (to !in 0 until strip.childCount) return
        slideAnim.finish()
        val neighbour = strip.getChildAt(to)
        val oldTile = tile.left
        val oldNeighbour = neighbour.left
        // Move the neighbour (not the held, focused tile) so focus is never lost.
        strip.removeViewAt(to)
        strip.addView(neighbour, from)
        strip.placeTiles()
        slideTiles.clear()
        slideTiles += tile to (oldTile - tile.left).toFloat()
        slideTiles += neighbour to (oldNeighbour - neighbour.left).toFloat()
        for ((v, dx) in slideTiles) v.translationX = dx
        slideAnim.start()
        scrollToChild(tile)
    }

    private fun finishMove(notify: Boolean) {
        val tile = moving ?: return
        moving = null
        tile.moving = false
        slideAnim.finish()
        if (notify) onOrderChanged?.invoke(strip.tiles().map { it.app.packageName })
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val target = lastFocused?.takeIf { it.parent === strip } ?: strip.getChildAt(0)
        return target?.requestFocus() ?: false
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (focused == null || focused.parent !== strip) return
        lastFocused = focused
        scrollToChild(focused)
    }

    private fun scrollToChild(focused: View) {
        if (width == 0) return
        val target = ScrollMath.targetOffset(
            itemStart = focused.left,
            itemEnd = focused.right,
            viewport = width,
            content = strip.measuredWidth,
            margin = gap + tileW / 2, // keep half of the neighbour peeking in
            current = if (scrollAnim.running) scrollTo else strip.translationX,
        )
        if (target == strip.translationX && !scrollAnim.running) return
        scrollFrom = strip.translationX
        scrollTo = target
        scrollAnim.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        strip.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), tileH + 2 * padV)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        strip.layout(0, padV, strip.measuredWidth, padV + tileH)
    }

    override fun shouldDelayChildPressedState() = false

    /** Lays tiles out left-to-right at fixed size; its width is the full content width. */
    private inner class Strip(context: Context) : ViewGroup(context) {
        init {
            clipChildren = false
            clipToPadding = false
        }

        fun tiles(): List<AppTileView> = (0 until childCount).map { getChildAt(it) as AppTileView }

        /** Position tiles by index right now (used after a swap, before the next layout pass). */
        fun placeTiles() {
            var x = padH
            for (i in 0 until childCount) {
                getChildAt(i).layout(x, 0, x + tileW, tileH)
                x += tileW + gap
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val tileSpecW = MeasureSpec.makeMeasureSpec(tileW, MeasureSpec.EXACTLY)
            val tileSpecH = MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) getChildAt(i).measure(tileSpecW, tileSpecH)
            val n = childCount
            val content = 2 * padH + n * tileW + (n - 1).coerceAtLeast(0) * gap
            setMeasuredDimension(content, tileH)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = placeTiles()
    }

    companion object {
        private const val SCROLL_MS = 180L
        private const val SLIDE_MS = 160L
    }
}
