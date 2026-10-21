package dev.tvlauncher.muse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MusePairingTest {
    private val pairing = MusePairing(
        accessToken = "device-access",
        refreshToken = "hatch_refresh:device-refresh",
        username = "someone",
        apiUrl = "",
        apiUrlV2 = "https://api.example",
        noiseHost = "hatch.example",
        accessTokenSavedAt = 1_760_000_000,
        sdkTokenHint = "mgst_AAAAAAA",
    )

    @Test
    fun `round trips through the stored json in the SDK's field order`() {
        val json = pairing.toJson()
        assertEquals(
            """{"access_token":"device-access","refresh_token":"hatch_refresh:device-refresh","token_type":"device",""" +
                """"username":"someone","api_url":"","api_url_v2":"https://api.example","noise_host":"hatch.example",""" +
                """"access_token_saved_at":1760000000,"sdk_token_hint":"mgst_AAAAAAA"}""",
            json,
        )
        assertEquals(pairing, MusePairing.fromJson(json))
    }

    @Test
    fun `a record without both tokens is no pairing`() {
        assertNull(MusePairing.fromJson("""{"access_token":"a"}"""))
        assertNull(MusePairing.fromJson("""{"access_token":"a","refresh_token":7}"""))
        assertNull(MusePairing.fromJson("not json"))
    }

    @Test
    fun `tokens stay out of toString`() {
        assertFalse(pairing.toString().contains("device-access"))
        assertFalse(pairing.toString().contains("device-refresh"))
    }

    @Test
    fun `sdk token hint is what gadgets muse ai shows`() {
        assertEquals("mgst_AAAAAAA", MusePairing.sdkTokenHint("mgst_" + "A".repeat(43)))
        assertEquals("none", MusePairing.sdkTokenHint(null))
        assertEquals("none", MusePairing.sdkTokenHint(""))
    }
}
