package dev.tvlauncher.firetv

/**
 * When to cover Amazon's home screen with ours. Fire OS won't let another app be Home, so
 * every time Amazon's home comes up (the Home button, or Back out of an app) ours goes over
 * it. Amazon's Settings is the exception: it opens by starting HOME itself and then a page
 * inside Amazon's launcher, and that has to show. Main thread only.
 */
class HomeDecider(
    private val amazonPackage: String,
    /** Amazon's home activity class, as window events name it. */
    private val amazonHome: String,
    private val ownPackage: String,
) {
    enum class Action { COVER, UNCOVER, NONE }

    private var settingsUntil = 0L

    /** A HOME start from the log. */
    fun onHomeStart(uid: Int, now: Long): Action {
        if (uid == ROOT_UID || uid == SYSTEM_UID) {
            settingsUntil = 0L
            return Action.COVER
        }
        // An app starting HOME: Amazon's Settings on its way to its page in the launcher.
        settingsUntil = now + SETTINGS_GRACE_MS
        return Action.NONE
    }

    /** A window came to the front. */
    fun onWindow(pkg: String, cls: String, now: Long): Action = when {
        pkg == ownPackage || pkg in PASS_THROUGH -> Action.NONE
        // Dialogs and panels sit over whatever's in front without replacing it.
        cls.startsWith("android.") -> Action.NONE
        pkg != amazonPackage -> Action.UNCOVER
        cls == amazonHome -> if (now < settingsUntil) Action.NONE else Action.COVER
        else -> {
            // One of Amazon's own pages in its launcher (Settings): let it show.
            settingsUntil = now + SETTINGS_GRACE_MS
            Action.UNCOVER
        }
    }

    companion object {
        const val SETTINGS_GRACE_MS = 3_000L
        private const val ROOT_UID = 0
        private const val SYSTEM_UID = 1000
        private val PASS_THROUGH = setOf("com.android.systemui", "android")
    }
}
