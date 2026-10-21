package dev.tvlauncher.muse.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.tvlauncher.R
import dev.tvlauncher.muse.MuseSecrets
import dev.tvlauncher.muse.PairingFlow
import dev.tvlauncher.muse.PairingState
import dev.tvlauncher.muse.PairingStep

/**
 * "Connect Muse" walkthrough: token check → steps for the phone → live pairing
 * progress (gadget name, 10-minute countdown, stage checklist) → done/failed. Opens
 * on a "connected" screen once the TV is paired.
 * Remote-friendly: one focused pill button at a time, Back closes and cancels.
 */
class MusePairingDialog(
    context: Context,
    private val secrets: MuseSecrets,
    private val flow: PairingFlow,
) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen) {
    private val ui = MuseViews(context)
    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val buttons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    private var countdown: TextView? = null
    private var deadline = 0L
    private val tick = object : Runnable {
        override fun run() {
            val left = ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            countdown?.text = context.getString(R.string.muse_time_left, left / 60, left % 60)
            if (left > 0) main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val panel = ui.panel().apply {
            setPadding(dp(40), dp(32), dp(40), dp(28))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply { setImageResource(R.drawable.logo_muse) }, LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(text(context.getString(R.string.muse_connect_title), 26f, bold = true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    leftMargin = dp(14)
                })
            })
            addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })
            addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        }
        setContentView(FrameLayout(context).apply {
            setBackgroundColor(0xC0000000.toInt())
            addView(panel, FrameLayout.LayoutParams(dp(600), WRAP_CONTENT, Gravity.CENTER))
        })
        showStart()
    }

    /** Runs on dismiss; owners keep the dismiss listener for themselves. */
    override fun onStop() {
        super.onStop()
        main.removeCallbacks(tick)
        flow.cancel()
    }

    private fun showStart() = when {
        secrets.sdkToken == null -> showNeedsToken()
        secrets.isPaired -> showPaired()
        else -> showReady()
    }

    private fun showNeedsToken() {
        reset()
        body.addView(text(context.getString(R.string.muse_needs_token), 17f))
        body.addView(code("mise run tv:muse-token"), spaced(14))
        setButtons(context.getString(R.string.muse_check_again) to ::showStart, context.getString(R.string.cancel) to ::dismiss)
    }

    private fun showPaired() {
        reset()
        body.addView(text(context.getString(R.string.muse_already_connected), 17f))
        setButtons(context.getString(R.string.muse_done) to ::dismiss, context.getString(R.string.muse_pair_again) to ::showReady)
    }

    private fun showReady() {
        reset()
        val steps = listOf(
            context.getString(R.string.muse_step_developer_mode),
            context.getString(R.string.muse_step_add_device, secrets.identity().bleName),
            context.getString(R.string.muse_step_finish),
        )
        steps.forEachIndexed { i, step -> body.addView(numbered(i + 1, step), spaced(if (i == 0) 0 else 12)) }
        setButtons(context.getString(R.string.muse_start_pairing) to ::startPairing, context.getString(R.string.cancel) to ::dismiss)
    }

    private fun startPairing() {
        flow.start { state -> if (isShowing) render(state) }
    }

    private fun render(state: PairingState) = when (state) {
        is PairingState.InProgress -> showProgress(state)
        PairingState.Done -> showDone()
        is PairingState.Failed -> showFailed(state.reason)
    }

    private fun showProgress(state: PairingState.InProgress) {
        reset()
        body.addView(text(context.getString(R.string.muse_choose_on_phone), 16f, alpha = 0.7f))
        body.addView(text(state.gadgetName, 30f, bold = true, mono = true), spaced(6))
        countdown = text("", 14f, alpha = 0.6f)
        body.addView(countdown, spaced(4))
        val labels = mapOf(
            PairingStep.WAITING_FOR_PHONE to R.string.muse_stage_waiting,
            PairingStep.SECURING to R.string.muse_stage_securing,
            PairingStep.CONFIRMING to R.string.muse_stage_confirming,
            PairingStep.SIGNING_IN to R.string.muse_stage_signing_in,
        )
        PairingStep.entries.forEachIndexed { i, step ->
            val mark = when {
                step.ordinal < state.step.ordinal -> "✓"
                step == state.step -> "•"
                else -> "·"
            }
            val alpha = if (step.ordinal <= state.step.ordinal) 1f else 0.45f
            body.addView(text("$mark   ${context.getString(labels.getValue(step))}", 17f, alpha = alpha), spaced(if (i == 0) 20 else 10))
        }
        setButtons(context.getString(R.string.cancel) to ::dismiss)
        deadline = state.deadlineMillis
        main.removeCallbacks(tick)
        tick.run()
    }

    private fun showDone() {
        reset()
        body.addView(text("✓  " + context.getString(R.string.muse_connected), 20f, bold = true))
        setButtons(context.getString(R.string.muse_done) to ::dismiss)
    }

    private fun showFailed(reason: String) {
        reset()
        body.addView(text(reason, 17f))
        setButtons(context.getString(R.string.muse_try_again) to ::showReady, context.getString(R.string.cancel) to ::dismiss)
    }

    private fun reset() {
        main.removeCallbacks(tick)
        countdown = null
        body.removeAllViews()
    }

    private fun setButtons(vararg actions: Pair<String, () -> Unit>) {
        buttons.removeAllViews()
        actions.forEachIndexed { i, (label, action) ->
            buttons.addView(pill(label, action), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                if (i > 0) leftMargin = dp(12)
            })
        }
        buttons.getChildAt(0)?.requestFocus()
    }

    private fun pill(label: String, onClick: () -> Unit) = ui.pill(label, onClick)

    private fun text(value: String, sp: Float, bold: Boolean = false, alpha: Float = 1f, mono: Boolean = false) =
        ui.text(value, sp, bold, alpha, mono)

    private fun code(value: String) = text(value, 17f, mono = true).apply {
        background = GradientDrawable().apply {
            cornerRadius = 10 * density
            setColor(0x1AFFFFFF)
        }
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    private fun numbered(n: Int, value: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(text("$n", 17f, bold = true, alpha = 0.6f), LinearLayout.LayoutParams(dp(28), WRAP_CONTENT))
        addView(text(value, 17f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun spaced(topDp: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(topDp) }

    private fun dp(v: Int) = ui.dp(v)
}
