package dev.tvlauncher.apps

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.TextView
import dev.tvlauncher.ui.FrameAnimator
import dev.tvlauncher.ui.ScrollMath

/**
 * "Continue watching": a label over one horizontal row of 16:9 cards built
 * from the TV's Watch Next list. Scrolls like the app row (one strip moved by
 * translationX); up/down leave the row through [onUp] / [onDown].
 */
class ContinueRowView(
    context: Context,
    label: String,
    private val cardW: Int,
    private val cardH: Int,
    private val gap: Int,
    private val padV: Int,
) : ViewGroup(context) {
    var onOpen: ((WatchNextItem) -> Unit)? = null
    var onUp: (() -> Boolean)? = null
    var onDown: (() -> Boolean)? = null

    private val density = resources.displayMetrics.density
    private val title = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(0xD9FFFFFF.toInt())
        setShadowLayer(6f, 0f, 1f, 0x66000000)
        gravity = Gravity.START
    }
    private val strip = Strip(context)
    private var lastFocused: View? = null
    private var scrollFrom = 0f
    private var scrollTo = 0f
    private val scrollAnim = FrameAnimator(SCROLL_MS) { f ->
        strip.translationX = scrollFrom + (scrollTo - scrollFrom) * f
    }

    init {
        clipChildren = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        addView(title)
        addView(strip)
    }

    val hasItems: Boolean get() = strip.childCount > 0

    fun setItems(items: List<Pair<WatchNextItem, Bitmap?>>) {
        val focusedKey = (findFocus() as? CardView)?.key
        val rememberedKey = (lastFocused as? CardView)?.key
        lastFocused = null
        strip.removeAllViews()
        for ((item, art) in items) {
            strip.addView(CardView(context, item, art).apply { setOnClickListener { onOpen?.invoke(item) } })
        }
        val restore = strip.cards().firstOrNull { it.key == (focusedKey ?: rememberedKey) }
        lastFocused = restore
        if (focusedKey != null) (restore ?: strip.getChildAt(0))?.requestFocus()
        visibility = if (items.isEmpty()) GONE else VISIBLE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && hasFocus()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> if (onUp?.invoke() == true) return true
                KeyEvent.KEYCODE_DPAD_DOWN -> if (onDown?.invoke() == true) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val target = lastFocused?.takeIf { it.parent === strip } ?: strip.getChildAt(0)
        return target?.requestFocus() ?: false
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (focused == null || focused.parent !== strip || width == 0) return
        lastFocused = focused
        val target = ScrollMath.targetOffset(
            itemStart = focused.left,
            itemEnd = focused.right,
            viewport = width,
            content = strip.measuredWidth,
            margin = gap + cardW / 2,
            current = if (scrollAnim.running) scrollTo else strip.translationX,
        )
        if (target == strip.translationX && !scrollAnim.running) return
        scrollFrom = strip.translationX
        scrollTo = target
        scrollAnim.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        title.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        strip.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(cardH, MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), title.measuredHeight + 2 * padV + cardH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val inset = (8 * density).toInt() // align the label with the first card's edge
        title.layout(inset, 0, inset + title.measuredWidth, title.measuredHeight)
        val top = title.measuredHeight + padV
        strip.layout(0, top, strip.measuredWidth, top + cardH)
    }

    private inner class Strip(context: Context) : ViewGroup(context) {
        init {
            clipChildren = false
        }

        fun cards(): List<CardView> = (0 until childCount).map { getChildAt(it) as CardView }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY)
            val h = MeasureSpec.makeMeasureSpec(cardH, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) getChildAt(i).measure(w, h)
            val inset = (8 * density).toInt()
            setMeasuredDimension(2 * inset + childCount * cardW + (childCount - 1).coerceAtLeast(0) * gap, cardH)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            var x = (8 * density).toInt()
            for (i in 0 until childCount) {
                getChildAt(i).layout(x, 0, x + cardW, cardH)
                x += cardW + gap
            }
        }
    }

    companion object {
        private const val SCROLL_MS = 180L
    }
}

/**
 * One Watch Next card as a single self-drawing View: art (pre-cropped to the
 * card's focused size), a bottom gradient with title + episode, an optional
 * progress bar, and the same white focus ring/scale as app tiles.
 */
@SuppressLint("ViewConstructor")
private class CardView(context: Context, val item: WatchNextItem, private val art: Bitmap?) : View(context) {
    val key = item.packageName + "|" + item.title
    private val density = resources.displayMetrics.density
    private val radius = 10 * density
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val artDst = Rect()
    private val scrimPaint = Paint()
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15 * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(4f, 0f, 1f, 0x80000000.toInt())
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9FFFFFF.toInt()
        textSize = 12.5f * density
        setShadowLayer(4f, 0f, 1f, 0x80000000.toInt())
    }
    private val trackPaint = Paint().apply { color = 0x40FFFFFF }
    private val barPaint = Paint().apply { color = 0xE6FFFFFF.toInt() }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = Color.WHITE
    }
    private val ringRect = RectF()
    private var scaleFrom = 1f
    private var scaleTo = 1f
    private val focusAnim = FrameAnimator(140) { f ->
        val s = scaleFrom + (scaleTo - scaleFrom) * f
        scaleX = s
        scaleY = s
    }

    init {
        isFocusable = true
        isClickable = true
        contentDescription = listOf(item.title, item.subtitle).filter { it.isNotEmpty() }.joinToString(", ")
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scrimPaint.shader = LinearGradient(0f, h * SCRIM_START, 0f, h.toFloat(), 0x00000000, 0xE0000000.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (art != null) {
            artDst.set(0, 0, width, height)
            canvas.drawBitmap(art, null, artDst, artPaint)
        } else {
            canvas.drawColor(0xFF22242A.toInt())
        }
        canvas.drawRect(0f, h * SCRIM_START, w, h, scrimPaint)
        val pad = 12 * density
        val progress = item.progress
        val bottom = h - pad - (if (progress != null) 8 * density else 0f)
        val maxW = w - 2 * pad
        if (item.subtitle.isNotEmpty()) {
            val sub = TextUtils.ellipsize(item.subtitle, subPaint, maxW, TextUtils.TruncateAt.END)
            canvas.drawText(sub, 0, sub.length, pad, bottom, subPaint)
        }
        val titleY = if (item.subtitle.isNotEmpty()) bottom - 18 * density else bottom
        val t = TextUtils.ellipsize(item.title, titlePaint, maxW, TextUtils.TruncateAt.END)
        canvas.drawText(t, 0, t.length, pad, titleY, titlePaint)
        if (progress != null) {
            val barTop = h - pad - 4 * density
            canvas.drawRect(pad, barTop, w - pad, barTop + 4 * density, trackPaint)
            canvas.drawRect(pad, barTop, pad + maxW * progress, barTop + 4 * density, barPaint)
        }
        if (isFocused) {
            val inset = ringPaint.strokeWidth / 2
            ringRect.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(ringRect, radius - inset, radius - inset, ringPaint)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        scaleFrom = scaleX
        scaleTo = if (gainFocus) FOCUS_SCALE else 1f
        focusAnim.start()
        invalidate()
    }

    companion object {
        const val FOCUS_SCALE = 1.1f
        private const val SCRIM_START = 0.4f // gradient covers the lower 60% behind the text
    }
}
