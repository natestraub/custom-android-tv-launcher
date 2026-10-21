package dev.tvlauncher.muse.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 (RFC 5869) for Muse pairing, plus the Noise protocol's HKDF
 * variant (Noise spec rev 34 §4.3). Android 11 has HMAC but no HKDF API.
 */
object Hkdf {
    private const val HASH_LEN = 32

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // RFC 5869: an empty salt means HashLen zero bytes (SecretKeySpec rejects empty keys).
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(HASH_LEN) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-Extract: PRK = HMAC(salt, IKM). */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    /** HKDF-Expand: T(1) || T(2) || … truncated to [length] bytes. */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..255 * HASH_LEN) { "HKDF length $length out of range" }
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val n = minOf(HASH_LEN, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }

    /** Extract + expand. */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)

    /** Noise HKDF with two outputs (MixKey, Split). */
    fun noise2(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val temp = hmac(chainingKey, ikm)
        val out1 = hmac(temp, byteArrayOf(1))
        val out2 = hmac(temp, out1 + byteArrayOf(2))
        return out1 to out2
    }

    /** Noise HKDF with three outputs (MixKeyAndHash; unused by XX, kept for completeness). */
    fun noise3(chainingKey: ByteArray, ikm: ByteArray): List<ByteArray> {
        val temp = hmac(chainingKey, ikm)
        val out1 = hmac(temp, byteArrayOf(1))
        val out2 = hmac(temp, out1 + byteArrayOf(2))
        val out3 = hmac(temp, out2 + byteArrayOf(3))
        return listOf(out1, out2, out3)
    }
}
