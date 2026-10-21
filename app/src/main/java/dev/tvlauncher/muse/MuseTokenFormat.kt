package dev.tvlauncher.muse

/** Muse Gadget SDK token shape: `mgst_` + 43 canonical base64url chars (32 bytes). */
object MuseTokenFormat {
    private val PATTERN = Regex("^mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")

    fun isValid(token: String) = PATTERN.matches(token)
}
