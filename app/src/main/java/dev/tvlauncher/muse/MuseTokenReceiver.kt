package dev.tvlauncher.muse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.tvlauncher.muse.link.MuseRuntime

/**
 * Receives the Muse SDK token from `scripts/muse-token.sh` (and the Meta speech-to-text
 * API key from `scripts/meta-asr-key.sh`) over adb, so nobody types a token with a remote. The manifest guards this receiver
 * with android.permission.DUMP, which only the system and the adb shell hold.
 * Replies through the broadcast result; never echoes or logs the token.
 */
class MuseTokenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val secrets = MuseSecrets(context)
        when (intent.action) {
            ACTION_SET -> {
                val token = intent.getStringExtra(EXTRA_TOKEN)?.trim()
                if (token == null || !MuseTokenFormat.isValid(token)) {
                    setResult(RESULT_REJECTED, "invalid token format", null)
                } else {
                    secrets.sdkToken = token
                    setResult(RESULT_OK, "saved", null)
                }
            }
            ACTION_CLEAR -> {
                secrets.sdkToken = null
                setResult(RESULT_OK, "cleared", null)
            }
            ACTION_STATUS -> setResult(RESULT_OK, if (secrets.sdkToken != null) "present" else "absent", null)
            ACTION_SET_ASR_KEY -> {
                val key = intent.getStringExtra(EXTRA_TOKEN)?.trim()
                if (key.isNullOrEmpty() || key.length > MAX_KEY_CHARS || key.any { it.isWhitespace() || it.isISOControl() }) {
                    setResult(RESULT_REJECTED, "invalid key format", null)
                } else {
                    secrets.metaApiKey = key
                    setResult(RESULT_OK, "saved", null)
                }
            }
            ACTION_CLEAR_ASR_KEY -> {
                secrets.metaApiKey = null
                setResult(RESULT_OK, "cleared", null)
            }
            ACTION_CLEAR_PAIRING -> {
                // Stop the link first so it can't keep using (or re-save) the old device tokens.
                MuseRuntime.stop()
                setResult(RESULT_OK, if (secrets.savePairing(null)) "unpaired" else "failed to clear", null)
            }
            ACTION_ASR_KEY_STATUS -> setResult(RESULT_OK, if (secrets.metaApiKey != null) "present" else "absent", null)
            else -> setResult(RESULT_REJECTED, "unknown action", null)
        }
    }

    companion object {
        const val ACTION_SET = "dev.tvlauncher.muse.SET_TOKEN"
        const val ACTION_CLEAR = "dev.tvlauncher.muse.CLEAR_TOKEN"
        const val ACTION_STATUS = "dev.tvlauncher.muse.TOKEN_STATUS"
        const val EXTRA_TOKEN = "token"

        /** Forgets this TV's Muse pairing (device tokens); the SDK token stays. Pair again afterwards. */
        const val ACTION_CLEAR_PAIRING = "dev.tvlauncher.muse.CLEAR_PAIRING"

        /** The Meta Model API key for speech-to-text, from scripts/meta-asr-key.sh. */
        const val ACTION_SET_ASR_KEY = "dev.tvlauncher.muse.SET_ASR_KEY"
        const val ACTION_CLEAR_ASR_KEY = "dev.tvlauncher.muse.CLEAR_ASR_KEY"
        const val ACTION_ASR_KEY_STATUS = "dev.tvlauncher.muse.ASR_KEY_STATUS"
        private const val MAX_KEY_CHARS = 512
        private const val RESULT_OK = 1
        private const val RESULT_REJECTED = 2
    }
}
