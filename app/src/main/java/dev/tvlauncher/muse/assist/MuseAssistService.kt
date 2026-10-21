package dev.tvlauncher.muse.assist

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * Muse as the TV's assistant: once this app holds the Assistant role, the remote's mic button
 * (and any other assist trigger) shows [MuseAssistSession]. The remote's protocol is in
 * docs/atv-remote-assist-protocol.md; the role is set with scripts/assistant-role.sh.
 */
class MuseAssistService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Muse doesn't read the screen: skip the foreground app's view tree and screenshot.
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        Log.i(TAG, "ready")
    }
}

class MuseAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = MuseAssistSession(this)
}

/** Has no window of its own: it opens [MuseAssistActivity] over whatever is playing. */
class MuseAssistSession(service: MuseAssistSessionService) : VoiceInteractionSession(service) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Never log or unparcel args: the remote's audio stream inside it is not our class (spec §3).
        Log.i(TAG, "shown, flags=$showFlags")
        startAssistantActivity(Intent(context, MuseAssistActivity::class.java))
        hide()
    }
}

private const val TAG = "MuseAssist"
