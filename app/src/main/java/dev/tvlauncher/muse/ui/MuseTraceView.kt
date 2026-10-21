package dev.tvlauncher.muse.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.view.Choreographer
import android.view.View

/**
 * The Muse "M" drawing itself while someone talks into the remote: one continuous stroke that
 * moves with the voice ([level]), over a faint copy of the whole letter. Vsync-driven through
 * Choreographer, so it stays smooth with the system's animations turned off.
 */
class MuseTraceView(context: Context) : View(context), Choreographer.FrameCallback {
    /** Loudness (frame RMS) of the latest audio; sets the pen's speed. */
    var level = 0.0

    private val pen = TracePen()
    private val letter = Path()
    private val stroke = Path()
    private val measure = PathMeasure()
    private var length = 0f
    private var lastFrameNanos = 0L
    private var running = false
    private var whole = false

    private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
        alpha = 38
    }
    private val ink = Paint(faint).apply { alpha = 255 }

    fun start() {
        whole = false
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /** Rest on the complete letter, e.g. once Muse has answered. */
    fun showWhole() {
        stop()
        whole = true
        invalidate()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos != 0L) pen.advance((frameTimeNanos - lastFrameNanos) / 1e9, level)
        lastFrameNanos = frameTimeNanos
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = minOf(w, h).toFloat()
        val scale = size / BOX
        letter.reset()
        letter.addPath(M_PATH, Matrix().apply {
            setScale(scale, scale)
            postTranslate((w - size) / 2, (h - size) / 2)
        })
        measure.setPath(letter, false)
        length = measure.length
        faint.strokeWidth = STROKE * scale
        ink.strokeWidth = STROKE * scale
    }

    override fun onDraw(canvas: Canvas) {
        if (whole) return canvas.drawPath(letter, ink)
        canvas.drawPath(letter, faint)
        if (pen.end <= pen.start) return
        stroke.reset()
        measure.getSegment((pen.start * length).toFloat(), (pen.end * length).toFloat(), stroke, true)
        canvas.drawPath(stroke, ink)
    }

    private companion object {
        /** The logo's single stroke, traced from logo_muse.png in its 160×160 box. */
        const val BOX = 160f
        const val STROKE = 13f
        val M_PATH = Path().apply {
            moveTo(22f, 76f) // tail, low on the left
            cubicTo(30f, 58f, 44f, 40f, 52f, 44f) // up to the first hump
            cubicTo(58f, 48f, 50f, 80f, 42f, 110f) // first down stroke
            cubicTo(52f, 80f, 72f, 40f, 82f, 46f) // second hump
            cubicTo(90f, 52f, 80f, 85f, 72f, 110f) // second down stroke
            cubicTo(84f, 84f, 100f, 58f, 108f, 58f) // third hump
            cubicTo(116f, 60f, 108f, 90f, 104f, 108f) // third down stroke
            cubicTo(108f, 114f, 120f, 100f, 132f, 80f) // closing flick up to the right
        }
    }
}
