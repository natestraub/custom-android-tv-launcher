package dev.tvlauncher.ui

import android.view.Choreographer

/**
 * Minimal vsync-driven tween. Unlike ValueAnimator it ignores the global
 * "animator duration scale", so the launcher stays smooth even with system
 * animations turned off — while those still speed up everything else.
 * Main thread only.
 */
class FrameAnimator(
    private val durationMs: Long,
    private val onFrame: (Float) -> Unit,
) : Choreographer.FrameCallback {
    private var startNanos = 0L
    private var onEnd: (() -> Unit)? = null
    var running = false
        private set

    fun start(onEnd: (() -> Unit)? = null) {
        cancel()
        this.onEnd = onEnd
        running = true
        startNanos = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun cancel() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    /** Jump to the end state now (runs the final frame and onEnd). */
    fun finish() {
        if (!running) return
        cancel()
        onFrame(1f)
        onEnd?.invoke()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val t = ((frameTimeNanos - startNanos) / 1_000_000f / durationMs).coerceIn(0f, 1f)
        val inv = 1f - t
        onFrame(1f - inv * inv * inv) // ease-out cubic
        if (t < 1f) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            running = false
            onEnd?.invoke()
        }
    }
}
