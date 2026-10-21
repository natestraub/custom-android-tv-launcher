package dev.tvlauncher.muse.ui

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import dev.tvlauncher.R
import dev.tvlauncher.muse.MusePairingFlow
import dev.tvlauncher.muse.MuseSecrets
import dev.tvlauncher.muse.link.MuseLinkService

/**
 * The quick bar's Muse button: the chat once this TV is paired, else the pairing walkthrough.
 * [onClosed] runs when the last of its dialogs closes (the chat can hand over to pairing).
 */
class MuseButton(private val activity: Activity, private val onClosed: (() -> Unit)? = null) {
    private var voiceCallback: ((String?) -> Unit)? = null
    private val shown = ArrayList<Dialog>()
    private var overlay: MuseVoiceOverlay? = null

    fun open() {
        if (!MuseSecrets(activity).isPaired) return openPairing()
        MuseLinkService.start(activity) // no-op when already running; retries now if offline
        show(MuseChatDialog(activity, ::requestVoice, ::openPairing))
    }

    /** The remote's mic button (Muse is the TV's assistant): listen, or finish listening. */
    fun micPressed() {
        overlay?.takeIf { it.isShowing }?.let { return it.onRemoteMic() }
        if (!MuseSecrets(activity).isPaired) return openPairing()
        MuseLinkService.start(activity)
        MuseVoiceOverlay(activity).also {
            overlay = it
            show(it)
        }.onRemoteMic()
    }

    /** Pass the activity's results here; true when it was this button's voice input. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_VOICE) return false
        val words = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        voiceCallback?.invoke(words.takeIf { resultCode == Activity.RESULT_OK })
        voiceCallback = null
        return true
    }

    private fun openPairing() {
        val secrets = MuseSecrets(activity)
        show(MusePairingDialog(activity, secrets, MusePairingFlow(activity, secrets)))
    }

    /** Closes its dialogs while their window still exists (call before the activity is destroyed). */
    fun dismissAll() {
        shown.toList().forEach { it.dismiss() }
    }

    private fun show(dialog: Dialog) {
        shown += dialog
        dialog.setOnDismissListener {
            if (overlay === dialog) overlay = null
            shown -= dialog
            if (shown.isEmpty()) onClosed?.invoke()
        }
        dialog.show()
    }

    /** The TV's own voice input (no microphone permission needed); [onWords] gets null if cancelled. */
    private fun requestVoice(onWords: (String?) -> Unit) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, activity.getString(R.string.muse_voice_prompt))
        try {
            voiceCallback = onWords
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQUEST_VOICE)
        } catch (e: ActivityNotFoundException) {
            voiceCallback = null
            onWords(null)
        }
    }

    private companion object {
        const val REQUEST_VOICE = 3
    }
}
