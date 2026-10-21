package dev.tvlauncher.muse.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Turns the remote's speech into text with Google's recognizer on the TV (it lives in
 * Google's assistant app and shows no UI of its own). It records the remote's mic itself;
 * the remote service lets Google's app do that. Words arrive while you talk, and Google
 * decides when you've finished. Main thread only; one use per instance.
 */
class GoogleSpeech(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onLevel(level: Double)

        /** The words so far. */
        fun onPartial(text: String)

        fun onFinal(text: String)

        fun onHeardNothing()

        /** Google's recognizer couldn't run; record a voice note instead. */
        fun onUnavailable()
    }

    enum class Outcome { HEARD_NOTHING, FALL_BACK }

    private var recognizer: SpeechRecognizer? = null
    private var ended = false

    /** False when Google's recognizer isn't on this TV (then [Listener.onUnavailable] isn't called). */
    fun start(): Boolean {
        val probe = Intent(RecognitionService.SERVICE_INTERFACE).setComponent(GOOGLE)
        if (context.packageManager.resolveService(probe, 0) == null) return false
        val speech = SpeechRecognizer.createSpeechRecognizer(context, GOOGLE)
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(levelFromDb(rmsdB))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partial: Bundle?) {
                firstResult(partial)?.let { if (!ended) listener.onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                val text = firstResult(results)
                end()
                if (text.isNullOrBlank()) listener.onHeardNothing() else listener.onFinal(text)
            }

            override fun onError(error: Int) {
                if (ended) return
                end()
                Log.i(TAG, "Google speech error $error")
                if (outcomeOf(error) == Outcome.HEARD_NOTHING) listener.onHeardNothing() else listener.onUnavailable()
            }
        })
        speech.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
        )
        return true
    }

    /** Stop listening now and use what was said (the mic button pressed again). */
    fun finish() {
        recognizer?.stopListening()
    }

    /** Stop and drop it (Back). */
    fun cancel() {
        if (ended) return
        recognizer?.cancel()
        end()
    }

    private fun end() {
        ended = true
        recognizer?.destroy()
        recognizer = null
    }

    private fun firstResult(bundle: Bundle?) =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    companion object {
        private const val TAG = "MuseVoice"
        private val GOOGLE = ComponentName(
            "com.google.android.katniss",
            "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService",
        )

        /** Silence isn't a failure; anything else means try recording a voice note instead. */
        fun outcomeOf(error: Int) = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Outcome.HEARD_NOTHING
            else -> Outcome.FALL_BACK
        }

        /** Google reports roughly -2 dB (quiet) to 10 dB (loud); the pen expects RMS up to 2,500. */
        fun levelFromDb(rmsdB: Float): Double = ((rmsdB + 2.0) / 12.0).coerceIn(0.0, 1.0) * 2500.0
    }
}
