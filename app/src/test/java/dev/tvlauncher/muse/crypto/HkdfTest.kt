package dev.tvlauncher.muse.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HkdfTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    // RFC 5869 Appendix A.1 (basic SHA-256 case)
    @Test
    fun `rfc5869 case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val prk = Hkdf.extract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.hex())
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hkdf.expand(prk, info, 42).hex(),
        )
        assertArrayEquals(Hkdf.expand(prk, info, 42), Hkdf.derive(ikm, salt, info, 42))
    }

    // RFC 5869 Appendix A.3 (zero-length salt and info: salt defaults to HashLen zeros)
    @Test
    fun `rfc5869 case 3 empty salt and info`() {
        val ikm = ByteArray(22) { 0x0b }
        val prk = Hkdf.extract(ByteArray(0), ikm)
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", prk.hex())
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Hkdf.expand(prk, ByteArray(0), 42).hex(),
        )
    }

    @Test
    fun `noise hkdf outputs chain HMAC as the Noise spec defines`() {
        val ck = ByteArray(32) { 1 }
        val ikm = ByteArray(32) { 2 }
        val (a, b) = Hkdf.noise2(ck, ikm)
        val temp = Hkdf.hmac(ck, ikm)
        assertArrayEquals(Hkdf.hmac(temp, byteArrayOf(1)), a)
        assertArrayEquals(Hkdf.hmac(temp, a + byteArrayOf(2)), b)
        val three = Hkdf.noise3(ck, ikm)
        assertArrayEquals(a, three[0])
        assertArrayEquals(b, three[1])
        assertArrayEquals(Hkdf.hmac(temp, b + byteArrayOf(3)), three[2])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `expand refuses more than 255 blocks`() {
        Hkdf.expand(ByteArray(32), ByteArray(0), 255 * 32 + 1)
    }
}
