package dev.tvlauncher.muse.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.tvlauncher.R
import dev.tvlauncher.muse.chat.ChatController
import dev.tvlauncher.muse.link.MuseRuntime
import dev.tvlauncher.muse.link.TvCommands
import dev.tvlauncher.muse.MuseSecrets
import dev.tvlauncher.muse.voice.GoogleSpeech
import dev.tvlauncher.muse.voice.MetaSpeech
import dev.tvlauncher.muse.voice.VoiceCapture

/**
 * What the remote's mic button shows: the Muse M at the top of the screen, drawing itself
 * while you talk, and one small bubble under it with the status and Muse's answer. Nothing to
 * focus; the mic button listens again (or finishes listening), Back closes it.
 */
class MuseVoiceOverlay(context: Context) :
    Dialog(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen), ChatController.View {
    private val ui = MuseViews(context)
    private val controller = ChatController(context, this)
    private val trace = MuseTraceView(context)
    private val status = ui.text("", 15f, alpha = 0.6f)
    private val answer = ui.text("", 19f).apply { setLineSpacing(0f, 1.25f) }
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
    private lateinit var bubble: LinearLayout
    private var meta: MetaSpeech? = null
    private var speech: GoogleSpeech? = null
    private var capture: VoiceCapture? = null
    private val onCommand: (String) -> Unit = { if (it in TvCommands.SCREEN_CHANGES && isShowing) dismiss() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        scroll.addView(answer)
        bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(14))
            background = GradientDrawable().apply {
                cornerRadius = ui.dp(20).toFloat()
                setColor(0xE61C1D21.toInt())
            }
            addView(status)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(4) })
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(trace, LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)))
            addView(bubble, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = ui.dp(12) })
        }
        setContentView(FrameLayout(context).apply {
            // Only the top of the screen darkens, enough to read the bubble over video.
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB3000000.toInt(), 0x00000000))
            addView(column, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = ui.dp(28)
            })
        })
        // The bubble hugs short text and stops growing at a comfortable reading width.
        answer.maxWidth = ui.dp(560)
        status.maxWidth = ui.dp(560)
        answer.visibility = View.GONE
        bubble.visibility = View.GONE
        scroll.viewTreeObserver.addOnGlobalLayoutListener { capScroll() }
        MuseRuntime.addCommandListener(onCommand)
    }

    override fun onStop() {
        super.onStop()
        meta?.cancel()
        meta = null
        speech?.cancel()
        speech = null
        capture?.cancel()
        capture = null
        trace.stop()
        MuseRuntime.removeCommandListener(onCommand)
        controller.close()
    }

    /**
     * The remote's mic was pressed: listen, or if already listening, send what was said.
     * Google's recognizer turns speech into text (shown as it's heard); if it can't run,
     * the question is recorded and sent as a voice note instead.
     */
    fun onRemoteMic() {
        meta?.let { return it.finish() }
        speech?.let { return it.finish() }
        capture?.let { return it.finish() }
        if (controller.busy) return
        answer.visibility = View.GONE
        setStatus(context.getString(R.string.muse_listening))
        trace.start()
        val metaKey = MuseSecrets(context).metaApiKey
        if (metaKey != null) return listenWithMeta(metaKey)
        val google = GoogleSpeech(context, object : GoogleSpeech.Listener {
            override fun onLevel(level: Double) {
                trace.level = level
            }

            override fun onPartial(text: String) = showWords(text)

            override fun onFinal(text: String) {
                speech = null
                trace.level = THINKING_LEVEL
                controller.ask(text, spoken = true)
            }

            override fun onHeardNothing() {
                speech = null
                showError(context.getString(R.string.muse_heard_nothing))
            }

            override fun onUnavailable() {
                speech = null
                recordVoiceNote()
            }
        })
        if (google.start()) speech = google else recordVoiceNote()
    }

    /** Meta's realtime speech-to-text: words appear as you talk, and the text goes to Muse. */
    private fun listenWithMeta(apiKey: String) {
        meta = MetaSpeech(apiKey, object : MetaSpeech.Listener {
            override fun onLevel(level: Double) {
                trace.level = level
            }

            override fun onPartial(text: String) = showWords(text)

            override fun onFinal(text: String) {
                meta = null
                trace.level = THINKING_LEVEL
                if (text.isBlank()) showError(context.getString(R.string.muse_heard_nothing)) else controller.ask(text, spoken = true)
            }

            override fun onHeardNothing() {
                meta = null
                showError(context.getString(R.string.muse_heard_nothing))
            }

            override fun onFallback(wav: ByteArray?) {
                meta = null
                if (wav == null) return showError(context.getString(R.string.muse_mic_failed))
                trace.level = THINKING_LEVEL
                controller.askVoice(wav, "")
            }
        }).also { it.start() }
    }

    /** The fallback: record the question and send it as audio. */
    private fun recordVoiceNote() {
        capture = VoiceCapture(object : VoiceCapture.Listener {
            override fun onLevel(level: Double) {
                trace.level = level
            }

            override fun onSpoken(wav: ByteArray) {
                capture = null
                trace.level = THINKING_LEVEL
                controller.askVoice(wav, "")
            }

            override fun onNothing(micFailed: Boolean) {
                capture = null
                showError(context.getString(if (micFailed) R.string.muse_mic_failed else R.string.muse_heard_nothing))
            }
        }).also { it.start() }
    }

    /** The question's words, dimmer than an answer; they stay until the answer replaces them. */
    private fun showWords(text: String) {
        answer.setTextColor(Color.WHITE)
        answer.alpha = 0.75f
        answer.text = text
        answer.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        bubble.visibility = View.VISIBLE
    }

    override fun showQuestion(text: String) = showWords(text)

    override fun showStatus(text: String?) {
        if (!controller.busy) return
        // Muse's activity text is a fragment that follows its name ("is working").
        setStatus(text?.let { if (it.first().isLowerCase()) "${context.getString(R.string.muse)} $it" else it } ?: context.getString(R.string.muse_sent))
    }

    override fun showReply(text: String, done: Boolean) {
        answer.setTextColor(Color.WHITE)
        answer.alpha = 1f
        answer.text = text
        answer.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        if (done) {
            status.visibility = View.GONE
            trace.showWhole()
        }
        bubble.visibility = View.VISIBLE
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun showError(message: String) {
        trace.showWhole()
        answer.alpha = 1f
        status.visibility = View.GONE
        answer.text = message
        answer.setTextColor(0xFFFFB4A9.toInt())
        answer.visibility = View.VISIBLE
        bubble.visibility = View.VISIBLE
    }

    private fun setStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
        bubble.visibility = View.VISIBLE
    }

    /** Long answers scroll inside the bubble instead of running down the screen. */
    private fun capScroll() {
        val max = ui.dp(260)
        val params = scroll.layoutParams
        val wanted = if (answer.height > max) max else WRAP_CONTENT
        if (params.height != wanted) {
            params.height = wanted
            scroll.layoutParams = params
        }
    }

    private companion object {
        /** While Muse works, the M keeps drawing at an unhurried pace. */
        const val THINKING_LEVEL = 700.0
    }
}
