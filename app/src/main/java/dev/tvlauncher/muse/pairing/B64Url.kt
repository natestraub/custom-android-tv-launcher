package dev.tvlauncher.muse.pairing

import java.util.Base64

/** Unpadded base64url, decoded exactly as strictly as the Muse firmware decodes it. */
object B64Url {
    const val MAX_CHARS = 4096
    const val MAX_CIPHERTEXT_CHARS = 16384
    private val ALPHABET = Regex("[A-Za-z0-9_-]+")

    fun encode(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /** Throws [IllegalArgumentException] on padding, the standard alphabet, or a bad length. */
    fun decode(text: String, maxChars: Int = MAX_CHARS): ByteArray {
        require(text.length in 1..maxChars) { "invalid base64url length" }
        require(text.length % 4 != 1 && ALPHABET.matches(text)) { "invalid base64url" }
        return Base64.getUrlDecoder().decode(text)
    }
}
