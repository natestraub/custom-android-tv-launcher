package dev.tvlauncher.muse.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.spec.ECFieldFp

/** B64Url, P256 and GadgetIdentity (ports of the SDK's encoding and identity tests). */
class PairingPrimitivesTest {
    @Test
    fun `b64url decode rejects what the firmware rejects`() {
        for (text in listOf("", "a", "ab=c", "ab+c", "ab/c", "a b", "abc\n")) {
            assertThrows(text, IllegalArgumentException::class.java) { B64Url.decode(text) }
        }
    }

    @Test
    fun `b64url round trip is unpadded`() {
        assertEquals("-_8", B64Url.encode(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
        assertArrayEquals(byteArrayOf(0xfb.toByte(), 0xff.toByte()), B64Url.decode("-_8"))
    }

    @Test
    fun `b64url length limit is per field`() {
        val long = "A".repeat(B64Url.MAX_CHARS + 4)
        assertThrows(IllegalArgumentException::class.java) { B64Url.decode(long) }
        assertEquals(3075, B64Url.decode(long, B64Url.MAX_CIPHERTEXT_CHARS).size)
    }

    @Test
    fun `p256 points round trip`() {
        val key = P256.generateKeyPair().public
        val encoded = P256.encodePublic(key)
        assertEquals(65, encoded.size)
        assertArrayEquals(encoded, P256.encodePublic(P256.decodePublic(encoded)))
    }

    @Test
    fun `p256 rejects anything but an uncompressed point on the curve`() {
        val good = P256.encodePublic(P256.generateKeyPair().public)
        val p = (P256.params.curve.field as ECFieldFp).p
        val xIsP = good.copyOf().also { p.toByteArray().takeLast(32).toByteArray().copyInto(it, 1) }
        val offCurve = good.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        val cases = listOf(
            byteArrayOf(4) + ByteArray(64),
            byteArrayOf(2) + good.copyOfRange(1, 33),
            byteArrayOf(3) + good.copyOfRange(1, 65),
            good.copyOf(64),
            xIsP,
            offCurve,
        )
        for (bad in cases) {
            assertThrows(IllegalArgumentException::class.java) { P256.decodePublic(bad) }
        }
        P256.decodePublic(good)
    }

    @Test
    fun `identity names share the suffix`() {
        val identity = GadgetIdentity("02:00:00:ab:cd:ef")
        assertEquals("homelink-abcdef", identity.nodeId)
        assertEquals("hatch-link:02:00:00:ab:cd:ef", identity.deviceId)
        assertEquals("MuseGadgetABCDEF", identity.bleName)
    }

    @Test
    fun `generated mac is locally administered unicast`() {
        val first = GadgetIdentity.generate { ByteArray(it) { 0xFF.toByte() } }.mac.substringBefore(":").toInt(16)
        assertEquals(0, first and 0x01)
        assertEquals(2, first and 0x02)
        assertEquals("02:01:02:03:04:05", GadgetIdentity.generate { n -> ByteArray(n) { it.toByte() } }.mac)
    }

    @Test
    fun `identity rejects a malformed mac`() {
        for (mac in listOf("nope", "02:00:00:AB:CD:EF", "02:00:00:ab:cd", "02-00-00-ab-cd-ef")) {
            assertThrows(mac, IllegalArgumentException::class.java) { GadgetIdentity(mac) }
        }
    }
}
