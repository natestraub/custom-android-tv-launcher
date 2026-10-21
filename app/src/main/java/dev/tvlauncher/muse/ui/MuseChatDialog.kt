package dev.tvlauncher.muse.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tvlauncher.R
import dev.tvlauncher.muse.chat.ChatController
import dev.tvlauncher.muse.link.LinkState
import dev.tvlauncher.muse.link.MuseRuntime
import dev.tvlauncher.muse.link.TvCommands

/**
 * Ask Muse from the couch: speak (the TV's voice input) or type, and the answer streams in.
 * Spoken questions are answered in Muse's own voice. Back closes it. (The remote's mic
 * button opens the lighter [MuseVoiceOverlay] instead.)
 */
class MuseChatDialog(
    context: Context,
    /** Runs the TV's voice input; the callback gets the words, or null if cancelled. */
    private val requestVoice: ((String?) -> Unit) -> Unit,
    private val openPairing: () -> Unit,
) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen), ChatController.View {
    private val ui = MuseViews(context)
    private val controller = ChatController(context, this)
    private val connection = ui.text("", 14f, alpha = 0.6f)
    private val hint = ui.text(context.getString(R.string.muse_chat_hint), 17f, alpha = 0.7f)
    private val question = ui.text("", 16f, alpha = 0.6f)
    private val answer = ui.text("", 21f).apply { setLineSpacing(0f, 1.25f) }
    private val status = ui.text("", 15f, alpha = 0.55f)
    private val scroll = ScrollView(context)
    private lateinit var input: EditText
    private lateinit var mic: View
    private val onLinkState: (LinkState) -> Unit = { showConnection(it) }
    private val onCommand: (String) -> Unit = { if (it in TvCommands.SCREEN_CHANGES && isShowing) dismiss() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.logo_muse) }, LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))
            addView(ui.text(context.getString(R.string.muse), 24f, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                leftMargin = ui.dp(12)
            })
            addView(connection)
        }
        val conversation = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(question, spaced(0))
            addView(answer, spaced(8))
            addView(status, spaced(10))
        }
        scroll.apply {
            isFocusable = true // so the remote can scroll a long answer
            isVerticalScrollBarEnabled = false
            addView(conversation)
        }
        mic = ui.iconPill(R.drawable.ic_mic, context.getString(R.string.muse_chat_speak)) { listen() }
        input = EditText(context).apply {
            setHint(R.string.muse_chat_type_hint)
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 17f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(0x1AFFFFFF)
            }
            setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10))
            setOnEditorActionListener { _, action, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_SEND || enter) {
                    submitTyped()
                    true
                } else {
                    false
                }
            }
        }
        val send = ui.iconPill(R.drawable.ic_send, context.getString(R.string.muse_chat_send)) { submitTyped() }
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(mic, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
            addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = ui.dp(12) })
            addView(send, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)).apply { leftMargin = ui.dp(12) })
        }
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(ui.pill(context.getString(R.string.muse_chat_pairing)) {
                dismiss()
                openPairing()
            })
        }
        val panel = ui.panel().apply {
            setPadding(ui.dp(36), ui.dp(28), ui.dp(36), ui.dp(24))
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(18) })
            addView(inputRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(22) })
            addView(footer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(16) })
        }
        setContentView(FrameLayout(context).apply {
            setBackgroundColor(0xC0000000.toInt())
            addView(panel, FrameLayout.LayoutParams(ui.dp(720), WRAP_CONTENT, Gravity.CENTER))
        })
        scroll.layoutParams.height = WRAP_CONTENT
        scroll.viewTreeObserver.addOnGlobalLayoutListener { capScroll() }
        question.visibility = View.GONE
        answer.visibility = View.GONE
        status.visibility = View.GONE
        MuseRuntime.addListener(onLinkState)
        MuseRuntime.addCommandListener(onCommand)
        mic.requestFocus()
    }

    /** Runs on dismiss; owners keep the dismiss listener for themselves. */
    override fun onStop() {
        super.onStop()
        MuseRuntime.removeListener(onLinkState)
        MuseRuntime.removeCommandListener(onCommand)
        controller.close()
    }

    override fun showQuestion(text: String) {
        hint.visibility = View.GONE
        question.text = "“$text”"
        question.visibility = View.VISIBLE
        answer.text = ""
        answer.visibility = View.GONE
        answer.setTextColor(Color.WHITE)
    }

    override fun showStatus(text: String?) {
        status.text = text ?: context.getString(R.string.muse_chat_thinking)
        status.visibility = if (controller.busy) View.VISIBLE else View.GONE
    }

    override fun showReply(text: String, done: Boolean) {
        answer.text = text
        answer.setTextColor(Color.WHITE)
        answer.visibility = View.VISIBLE
        if (done) status.visibility = View.GONE
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun showError(message: String) {
        answer.text = message
        answer.setTextColor(0xFFFFB4A9.toInt())
        answer.visibility = View.VISIBLE
        status.visibility = View.GONE
    }

    private fun listen() {
        if (controller.busy) return
        requestVoice { words ->
            if (!words.isNullOrBlank() && isShowing) {
                controller.ask(words, spoken = true)
                status.visibility = View.VISIBLE
            }
        }
    }

    private fun submitTyped() {
        val text = input.text.toString()
        if (text.isBlank() || controller.busy) return
        input.setText("")
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        controller.ask(text)
        status.visibility = View.VISIBLE
        mic.post { mic.requestFocus() } // after the keyboard has let go of the field
    }

    private fun showConnection(state: LinkState) {
        connection.text = context.getString(
            when (state) {
                LinkState.Connected -> R.string.muse_chat_connected
                LinkState.Connecting -> R.string.muse_chat_connecting
                is LinkState.Offline -> R.string.muse_chat_offline
                LinkState.NotPaired -> R.string.muse_chat_not_paired
            },
        )
    }

    /** Long answers scroll inside the panel instead of pushing it off screen. */
    private fun capScroll() {
        val max = ui.dp(300)
        val params = scroll.layoutParams
        val wanted = if (scroll.getChildAt(0).height > max) max else WRAP_CONTENT
        if (params.height != wanted) {
            params.height = wanted
            scroll.layoutParams = params
        }
    }

    private fun spaced(topDp: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(topDp) }
}
