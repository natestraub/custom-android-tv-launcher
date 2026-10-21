package dev.tvlauncher.muse.crypto

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import javax.crypto.KeyAgreement
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class X25519Test {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    // RFC 7748 §5.2
    @Test
    fun `rfc7748 scalar multiplication vectors`() {
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            X25519.scalarMult(
                hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
            ).hex(),
        )
        // This u has its top bit set, which X25519 must mask rather than reject.
        assertEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            X25519.scalarMult(
                hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"),
            ).hex(),
        )
    }

    // RFC 7748 §5.2: start with k = u = 9, then k, u = X25519(k, u), k.
    @Test
    fun `rfc7748 iterated vectors after 1 and 1000 rounds`() {
        var k = ByteArray(32).also { it[0] = 9 }
        var u = k.copyOf()
        for (round in 1..1000) {
            val next = X25519.scalarMult(k, u)
            u = k
            k = next
            if (round == 1) assertEquals("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079", k.hex())
        }
        assertEquals("684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51", k.hex())
    }

    // RFC 7748 §6.1
    @Test
    fun `rfc7748 alice and bob agree on the shared secret`() {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePublic = X25519.publicKey(alice)
        val bobPublic = X25519.publicKey(bob)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alicePublic.hex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bobPublic.hex())
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, X25519.sharedSecret(alice, bobPublic).hex())
        assertEquals(shared, X25519.sharedSecret(bob, alicePublic).hex())
    }

    @Test
    fun `public keys match the noise_xx vector's fixed keys`() {
        val vector = JSONObject(javaClass.getResource("/muse/extra_vectors.json")!!.readText())
            .getJSONObject("noise_xx")
        for (role in listOf("initiator_ephemeral", "initiator_static", "responder_ephemeral", "responder_static")) {
            assertEquals(
                vector.getString("${role}_public_hex"),
                X25519.publicKey(hex(vector.getString("${role}_private_hex"))).hex(),
            )
        }
    }

    // The host JDK has had XDH since Java 11 (Android only since API 33): an independent oracle.
    @Test
    fun `matches the JDK's XDH on random keys`() {
        val generator = KeyPairGenerator.getInstance("X25519")
        repeat(16) {
            val alice = generator.generateKeyPair()
            val bob = generator.generateKeyPair()
            val alicePrivate = (alice.private as XECPrivateKey).scalar.get()
            val bobPublic = (bob.public as XECPublicKey).u.toLittleEndian()
            assertArrayEquals((alice.public as XECPublicKey).u.toLittleEndian(), X25519.publicKey(alicePrivate))
            val jdkSecret = KeyAgreement.getInstance("XDH").run {
                init(alice.private)
                doPhase(bob.public, true)
                generateSecret()
            }
            assertArrayEquals(jdkSecret, X25519.sharedSecret(alicePrivate, bobPublic))
        }
    }

    @Test
    fun `rejects each low-order point the spec lists`() {
        val privateKey = X25519.generateKeyPair().privateKey
        for (point in SPEC_LOW_ORDER_POINTS) {
            assertThrows(point, LowOrderPointException::class.java) { X25519.sharedSecret(privateKey, hex(point)) }
        }
    }

    @Test
    fun `rejects an all-zero secret from low-order points missing from the list`() {
        val privateKey = X25519.generateKeyPair().privateKey
        // Listed points with the ignored top bit set: not byte-equal to the list, same order.
        for (point in listOf("00".repeat(31) + "80", "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b880")) {
            assertArrayEquals(ByteArray(32), X25519.scalarMult(privateKey, hex(point)))
            assertThrows(point, LowOrderPointException::class.java) { X25519.sharedSecret(privateKey, hex(point)) }
        }
    }

    // RFC 7748 §5: non-canonical u (≥ p) must be treated as reduced mod p.
    @Test
    fun `non-canonical u acts like its value mod p`() {
        val k = X25519.generateKeyPair().privateKey
        val nine = ByteArray(32).also { it[0] = 9 }
        val pPlusNine = hex("f6" + "ff".repeat(30) + "7f") // 2^255 − 19 + 9
        assertArrayEquals(X25519.scalarMult(k, nine), X25519.scalarMult(k, pPlusNine))
    }

    @Test
    fun `clamping clears the low three bits and bit 255 and sets bit 254`() {
        assertEquals("f8" + "ff".repeat(30) + "7f", X25519.clamp(ByteArray(32) { -1 }).hex())
        assertEquals("00".repeat(31) + "40", X25519.clamp(ByteArray(32)).hex())
        // Scalar multiplication clamps internally, so raw and clamped scalars agree.
        val raw = ByteArray(32) { (it * 37 + 5).toByte() }
        assertArrayEquals(X25519.publicKey(raw), X25519.publicKey(X25519.clamp(raw)))
    }

    @Test
    fun `generated key pairs are fresh and agree with each other`() {
        val a = X25519.generateKeyPair()
        val b = X25519.generateKeyPair()
        assertArrayEquals(X25519.publicKey(a.privateKey), a.publicKey)
        assertFalse(a.privateKey.contentEquals(b.privateKey))
        assertArrayEquals(X25519.sharedSecret(a.privateKey, b.publicKey), X25519.sharedSecret(b.privateKey, a.publicKey))
    }

    @Test
    fun `rejects inputs that are not 32 bytes`() {
        assertThrows(IllegalArgumentException::class.java) { X25519.publicKey(ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { X25519.sharedSecret(ByteArray(32), ByteArray(33)) }
    }

    private fun BigInteger.toLittleEndian(): ByteArray {
        val bigEndian = toByteArray()
        return ByteArray(32) { if (it < bigEndian.size) bigEndian[bigEndian.size - 1 - it] else 0 }
    }

    private companion object {
        // docs/muse-protocol.md §3.4.2 (upstream's X25519_LOW_ORDER_POINTS)
        val SPEC_LOW_ORDER_POINTS = listOf(
            "00".repeat(32),
            "01" + "00".repeat(31),
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
            "ec" + "ff".repeat(30) + "7f",
            "ed" + "ff".repeat(30) + "7f",
            "ee" + "ff".repeat(30) + "7f",
        )
    }
}
