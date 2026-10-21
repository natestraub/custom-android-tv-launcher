package dev.tvlauncher.ui

import android.view.Choreographer
import android.view.View
import kotlin.random.Random

/** One pose of the pan-and-zoom: view scale plus translation in px. */
data class KenBurnsFrame(val scale: Float, val translateX: Float, val translateY: Float)

/**
 * A slow zoom from [startScale] to [endScale] while panning between two focal
 * points. Focal values are -1..1 fractions of the overflow available at the
 * current scale, so an edge of the image can never come into view.
 */
data class KenBurnsPath(
    val startScale: Float,
    val endScale: Float,
    val startFocusX: Float,
    val startFocusY: Float,
    val endFocusX: Float,
    val endFocusY: Float,
) {
    fun frame(progress: Float, width: Float, height: Float): KenBurnsFrame {
        val t = progress.coerceIn(0f, 1f)
        val scale = startScale + (endScale - startScale) * t
        val fx = startFocusX + (endFocusX - startFocusX) * t
        val fy = startFocusY + (endFocusY - startFocusY) * t
        val overflow = scale - 1f
        return KenBurnsFrame(scale, fx * overflow * width / 2f, fy * overflow * height / 2f)
    }

    companion object {
        /** Zoom in or out (randomly) between 1x and [maxScale], drifting toward a random spot. */
        fun random(rng: Random, maxScale: Float): KenBurnsPath {
            val near = 1f + (maxScale - 1f) * 0.15f
            val zoomIn = rng.nextBoolean()
            fun focus() = rng.nextFloat() * 2f - 1f
            return KenBurnsPath(
                startScale = if (zoomIn) near else maxScale,
                endScale = if (zoomIn) maxScale else near,
                startFocusX = focus(),
                startFocusY = focus(),
                endFocusX = focus(),
                endFocusY = focus(),
            )
        }
    }
}

/**
 * Drives a [KenBurnsPath] on a view's RenderNode properties (scale/translation:
 * no invalidate, no re-record). Runs at ~30 fps — the motion is a pixel or
 * two per second, so every other vsync is plenty and halves the GPU cost.
 */
class KenBurnsAnimator(private val view: View, private val maxScale: Float) : Choreographer.FrameCallback {
    private var path: KenBurnsPath? = null
    private var durationMs = 0L
    private var elapsedMs = 0L
    private var lastFrameNanos = 0L
    private var running = false

    /** New random motion over [durationMs]. */
    fun start(durationMs: Long) {
        path = KenBurnsPath.random(Random.Default, maxScale)
        this.durationMs = durationMs
        elapsedMs = 0L
        apply()
        resume()
    }

    /** Continue the current motion (e.g. after the launcher comes back to the front). */
    fun resume() {
        if (running || path == null) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun pause() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /** Stop and return the view to rest. */
    fun clear() {
        pause()
        path = null
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos
        val deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000
        if (deltaMs >= FRAME_INTERVAL_MS) {
            elapsedMs += deltaMs
            lastFrameNanos = frameTimeNanos
            apply()
        }
        if (elapsedMs < durationMs) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            running = false
        }
    }

    private fun apply() {
        val f = path?.frame(elapsedMs / durationMs.toFloat(), view.width.toFloat(), view.height.toFloat()) ?: return
        view.scaleX = f.scale
        view.scaleY = f.scale
        view.translationX = f.translateX
        view.translationY = f.translateY
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 30L
    }
}
