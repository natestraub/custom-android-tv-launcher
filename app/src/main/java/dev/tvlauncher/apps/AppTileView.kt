package dev.tvlauncher.apps

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import dev.tvlauncher.ui.FrameAnimator

/**
 * One app tile as a single View that draws its slice of the shared tile atlas
 * (no child views, no layout pass, no per-frame drawable work). Tiles are rendered
 * at the focused size, so the focus scale-up stays sharp. Focus = scale on
 * the RenderNode + a white ring, both cheap.
 */
@SuppressLint("ViewConstructor")
class AppTileView(
    context: Context,
    val app: AppEntry,
    private val radius: Float,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tileDst = Rect()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = Color.WHITE
    }

    // Dark hairline inside the ring so focus still reads on white banners.
    private val ringEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0x99000000.toInt()
    }
    private val ringRect = RectF()

    /** Picked up in move mode: lifted higher, thicker ring. */
    var moving = false
        set(value) {
            if (field == value) return
            field = value
            animateScale(if (value) MOVE_SCALE else if (isFocused) FOCUS_SCALE else 1f)
            invalidate()
        }
    private var scaleFrom = 1f
    private var scaleTo = 1f
    private val focusAnim = FrameAnimator(FOCUS_MS) { f ->
        val s = scaleFrom + (scaleTo - scaleFrom) * f
        scaleX = s
        scaleY = s
    }

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
        contentDescription = app.label
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
    }

    override fun onDraw(canvas: Canvas) {
        tileDst.set(0, 0, width, height)
        canvas.drawBitmap(app.atlas, app.tileSrc, tileDst, tilePaint)
        if (isFocused) {
            ringPaint.strokeWidth = (if (moving) 4.5f else 3f) * density
            val inset = ringPaint.strokeWidth / 2
            ringRect.set(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(ringRect, radius - inset, radius - inset, ringPaint)
            val edge = ringPaint.strokeWidth + ringEdgePaint.strokeWidth / 2
            ringRect.set(edge, edge, width - edge, height - edge)
            canvas.drawRoundRect(ringRect, radius - edge, radius - edge, ringEdgePaint)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animateScale(if (gainFocus) FOCUS_SCALE else 1f)
        invalidate()
    }

    private fun animateScale(target: Float) {
        scaleFrom = scaleX
        scaleTo = target
        focusAnim.start()
    }

    companion object {
        const val FOCUS_SCALE = 1.12f
        private const val MOVE_SCALE = 1.2f
        private const val FOCUS_MS = 140L
    }
}
