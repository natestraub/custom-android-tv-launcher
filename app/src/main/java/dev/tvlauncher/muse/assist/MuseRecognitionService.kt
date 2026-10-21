package dev.tvlauncher.muse.assist

import android.content.Intent
import android.os.RemoteException
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Android requires an assistant to ship a speech recognizer. Muse has none yet, so this one
 * declines every request; the TV's voice-input screen (RecognizerIntent) is unaffected.
 */
class MuseRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: RemoteException) {
            // the caller is gone
        }
    }

    override fun onCancel(listener: Callback) = Unit

    override fun onStopListening(listener: Callback) = Unit
}
