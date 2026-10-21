package dev.tvlauncher.muse.assist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.tvlauncher.muse.ui.MuseButton

/**
 * The see-through screen the assistant opens over the current app on a mic press. It listens
 * to the remote and asks Muse (or shows pairing, if the TV isn't paired), and goes away with
 * its last dialog.
 */
class MuseAssistActivity : Activity() {
    private val museButton by lazy { MuseButton(this, onClosed = ::close) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        museButton.micPressed()
    }

    /** The mic was pressed again while this is open (the assistant re-launches it). */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        museButton.micPressed()
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        museButton.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * Android removes this screen when another app comes to the front (e.g. one Muse opened);
     * its dialogs must close first, or a late dismiss crashes on the missing window.
     */
    override fun onDestroy() {
        museButton.dismissAll()
        super.onDestroy()
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
