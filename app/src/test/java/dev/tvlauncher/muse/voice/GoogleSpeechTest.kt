package dev.tvlauncher.muse.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSpeechTest {
    @Test
    fun `silence and no match mean nothing was heard, not a broken recognizer`() {
        assertEquals(GoogleSpeech.Outcome.HEARD_NOTHING, GoogleSpeech.outcomeOf(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(GoogleSpeech.Outcome.HEARD_NOTHING, GoogleSpeech.outcomeOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    @Test
    fun `other errors fall back to recording a voice note`() {
        for (error in listOf(
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
        )) {
            assertEquals(GoogleSpeech.Outcome.FALL_BACK, GoogleSpeech.outcomeOf(error))
        }
    }

    @Test
    fun `loudness in dB maps onto the pen's speech scale`() {
        assertEquals(0.0, GoogleSpeech.levelFromDb(-2f), 1e-9)
        assertEquals(0.0, GoogleSpeech.levelFromDb(-10f), 1e-9)
        assertTrue(GoogleSpeech.levelFromDb(4f) in 1000.0..1500.0)
        assertEquals(2500.0, GoogleSpeech.levelFromDb(10f), 1e-9)
        assertEquals(2500.0, GoogleSpeech.levelFromDb(30f), 1e-9)
    }
}
