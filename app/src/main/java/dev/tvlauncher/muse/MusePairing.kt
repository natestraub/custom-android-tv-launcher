package dev.tvlauncher.muse

import dev.tvlauncher.muse.wire.MessageCodec
import dev.tvlauncher.muse.wire.parseJsonObject

/**
 * What pairing saves (the SDK's pairing.json): the device tokens and endpoints the
 * cloud link uses. Kept encrypted in [MuseSecrets]; [toString] leaves the tokens out.
 */
data class MusePairing(
    val accessToken: String,
    val refreshToken: String,
    val username: String,
    val apiUrl: String,
    val apiUrlV2: String,
    val noiseHost: String,
    /** When [accessToken] was issued, in epoch seconds. */
    val accessTokenSavedAt: Long,
    /** The part of the SDK token gadgets.muse.ai shows, recorded at pairing. */
    val sdkTokenHint: String,
) {
    override fun toString() = "MusePairing(username=$username, apiUrlV2=$apiUrlV2, noiseHost=$noiseHost)"

    fun toJson(): String = MessageCodec.compactJson(
        linkedMapOf(
            "access_token" to accessToken,
            "refresh_token" to refreshToken,
            "token_type" to "device",
            "username" to username,
            "api_url" to apiUrl,
            "api_url_v2" to apiUrlV2,
            "noise_host" to noiseHost,
            "access_token_saved_at" to accessTokenSavedAt,
            "sdk_token_hint" to sdkTokenHint,
        ),
    )

    companion object {
        /** Null unless [text] holds both device tokens. */
        fun fromJson(text: String): MusePairing? {
            val o = parseJsonObject(text.toByteArray()) ?: return null
            fun string(key: String) = o.opt(key) as? String ?: ""
            if (string("access_token").isEmpty() || string("refresh_token").isEmpty()) return null
            return MusePairing(
                accessToken = string("access_token"),
                refreshToken = string("refresh_token"),
                username = string("username"),
                apiUrl = string("api_url"),
                apiUrlV2 = string("api_url_v2"),
                noiseHost = string("noise_host"),
                accessTokenSavedAt = (o.opt("access_token_saved_at") as? Number)?.toLong() ?: 0,
                sdkTokenHint = string("sdk_token_hint").ifEmpty { "none" },
            )
        }

        /** The part of an SDK token gadgets.muse.ai shows, or "none" without one. */
        fun sdkTokenHint(token: String?) = token?.take(12)?.ifEmpty { null } ?: "none"
    }
}
