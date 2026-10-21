package dev.tvlauncher.muse.pairing

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/** NIST P-256 for the pairing handshake: X9.62 uncompressed points and ECDH. */
object P256 {
    const val POINT_BYTES = 65
    private const val COORDINATE_BYTES = 32

    /**
     * Curve parameters taken from a generated key rather than written out, so every key
     * here uses the provider's own named curve (Android's provider can treat a hand-built
     * spec as a separate custom curve).
     */
    val params: ECParameterSpec = (generateKeyPair().public as ECPublicKey).params

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    /** Parses `04 ‖ X ‖ Y`; throws [IllegalArgumentException] unless it is a point on the curve. */
    fun decodePublic(encoded: ByteArray): ECPublicKey {
        require(encoded.size == POINT_BYTES && encoded[0] == 0x04.toByte()) { "not an uncompressed P-256 point" }
        val x = BigInteger(1, encoded.copyOfRange(1, 1 + COORDINATE_BYTES))
        val y = BigInteger(1, encoded.copyOfRange(1 + COORDINATE_BYTES, POINT_BYTES))
        val curve = params.curve
        val p = (curve.field as ECFieldFp).p
        require(x < p && y < p) { "coordinate out of range" }
        val rhs = x.pow(3).add(curve.a.multiply(x)).add(curve.b).mod(p)
        require(y.multiply(y).mod(p) == rhs) { "point is not on P-256" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    fun encodePublic(key: PublicKey): ByteArray {
        val point = (key as ECPublicKey).w
        return byteArrayOf(0x04) + fixed(point.affineX) + fixed(point.affineY)
    }

    /** The ECDH shared secret: the 32-byte X coordinate. */
    fun ecdh(private: PrivateKey, peer: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(private)
        doPhase(peer, true)
        generateSecret()
    }.also { check(it.size == COORDINATE_BYTES) { "unexpected ECDH secret size" } }

    private fun fixed(n: BigInteger): ByteArray {
        val raw = n.toByteArray() // big-endian; may carry a sign byte or be shorter than 32
        return when {
            raw.size > COORDINATE_BYTES -> raw.copyOfRange(raw.size - COORDINATE_BYTES, raw.size)
            else -> ByteArray(COORDINATE_BYTES - raw.size) + raw
        }
    }
}
