package dev.tvlauncher.muse.crypto

import java.security.InvalidKeyException
import java.security.MessageDigest
import java.security.SecureRandom

/** The peer's X25519 public key has small order, so the shared secret would be predictable. */
class LowOrderPointException(message: String) : InvalidKeyException(message)

/** Raw RFC 7748 keys, 32 little-endian bytes each. The private key is clamped on use. */
class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

/**
 * X25519 (RFC 7748) for the Muse Noise handshake. Vendored because Android only ships
 * `XDH` from API 33 and the launcher takes no crypto libraries. A port of TweetNaCl's
 * `crypto_scalarmult`, whose X25519 has a machine-checked correctness proof: field
 * elements are 16 × 16-bit limbs, and the Montgomery ladder swaps with masks instead
 * of branches, so its timing doesn't depend on the scalar.
 */
object X25519 {
    const val KEY_LEN = 32

    private val BASE_POINT = ByteArray(KEY_LEN).also { it[0] = 9 }

    /** (A − 2) / 4 = 121665 in limbs. */
    private val A24 = LongArray(16).also {
        it[0] = 0xdb41
        it[1] = 1
    }

    /**
     * Upstream's `X25519_LOW_ORDER_POINTS` (libsodium's blocklist). The all-zero check in
     * [sharedSecret] catches every other encoding of a small-order point.
     */
    private val LOW_ORDER_POINTS = listOf(
        "00".repeat(32),
        "01" + "00".repeat(31),
        "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
        "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
        "ec" + "ff".repeat(30) + "7f",
        "ed" + "ff".repeat(30) + "7f",
        "ee" + "ff".repeat(30) + "7f",
    ).map { hex -> ByteArray(KEY_LEN) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() } }

    private val defaultRandom = SecureRandom()

    fun generateKeyPair(random: SecureRandom = defaultRandom): X25519KeyPair {
        val privateKey = ByteArray(KEY_LEN).also(random::nextBytes)
        return X25519KeyPair(privateKey, publicKey(privateKey))
    }

    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE_POINT)

    /**
     * Diffie-Hellman with upstream's checks: throws [LowOrderPointException] when [peerPublic]
     * is a listed low-order point or the result is all zeros.
     */
    fun sharedSecret(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        require(peerPublic.size == KEY_LEN) { "X25519 public key must be $KEY_LEN bytes" }
        if (LOW_ORDER_POINTS.any { MessageDigest.isEqual(it, peerPublic) }) {
            throw LowOrderPointException("rejected low-order public key")
        }
        val shared = scalarMult(privateKey, peerPublic)
        // OR every byte rather than stopping early: the secret shouldn't steer timing.
        if (shared.fold(0) { acc, b -> acc or b.toInt() } == 0) {
            throw LowOrderPointException("shared secret is all zeros")
        }
        return shared
    }

    /** RFC 7748 decodeScalar25519: clear the low three bits and bit 255, set bit 254. */
    fun clamp(scalar: ByteArray): ByteArray = scalar.copyOf().also {
        it[0] = (it[0].toInt() and 0xf8).toByte()
        it[31] = (it[31].toInt() and 0x7f or 0x40).toByte()
    }

    /** The bare X25519 function: no checks on [u] beyond masking its top bit, as RFC 7748 says. */
    fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == KEY_LEN && u.size == KEY_LEN) { "X25519 inputs must be $KEY_LEN bytes" }
        val k = clamp(scalar)
        val x1 = unpack(u)
        val x2 = LongArray(16).also { it[0] = 1 }
        val z2 = LongArray(16)
        val x3 = x1.copyOf()
        val z3 = LongArray(16).also { it[0] = 1 }
        val e = LongArray(16)
        val f = LongArray(16)
        val t = LongArray(31)
        for (i in 254 downTo 0) {
            val bit = (k[i shr 3].toInt() shr (i and 7)) and 1
            cswap(x2, x3, bit)
            cswap(z2, z3, bit)
            // One ladder step (RFC 7748 §5), with TweetNaCl's register reuse.
            add(e, x2, z2)
            sub(x2, x2, z2)
            add(z2, x3, z3)
            sub(x3, x3, z3)
            mul(z3, e, e, t)
            mul(f, x2, x2, t)
            mul(x2, z2, x2, t)
            mul(z2, x3, e, t)
            add(e, x2, z2)
            sub(x2, x2, z2)
            mul(x3, x2, x2, t)
            sub(z2, z3, f)
            mul(x2, z2, A24, t)
            add(x2, x2, z3)
            mul(z2, z2, x2, t)
            mul(x2, z3, f, t)
            mul(z3, x3, x1, t)
            mul(x3, e, e, t)
            cswap(x2, x3, bit)
            cswap(z2, z3, bit)
        }
        invert(z2, z2, t)
        mul(x2, x2, z2, t)
        return pack(x2)
    }

    private fun unpack(bytes: ByteArray): LongArray {
        val o = LongArray(16) { (bytes[2 * it].toLong() and 0xff) or ((bytes[2 * it + 1].toLong() and 0xff) shl 8) }
        o[15] = o[15] and 0x7fff
        return o
    }

    /** Reduces fully mod p = 2^255 − 19 (subtracting p twice, branch-free) and encodes little-endian. */
    private fun pack(n: LongArray): ByteArray {
        val t = n.copyOf()
        carry(t)
        carry(t)
        carry(t)
        val m = LongArray(16)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val borrow = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            cswap(t, m, 1 - borrow)
        }
        return ByteArray(KEY_LEN) { (t[it / 2] shr (8 * (it % 2))).toByte() }
    }

    /** Brings each limb back to 16 bits; the top carry wraps around as ×38, since 2^256 ≡ 38. */
    private fun carry(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    /** Swaps [p] and [q] when [bit] is 1, without branching on it. */
    private fun cswap(p: LongArray, q: LongArray, bit: Int) {
        val mask = -bit.toLong()
        for (i in 0 until 16) {
            val x = mask and (p[i] xor q[i])
            p[i] = p[i] xor x
            q[i] = q[i] xor x
        }
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] + b[i]
    }

    private fun sub(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] - b[i]
    }

    /** o = a·b mod p; [t] is scratch so the ladder doesn't allocate. [o] may alias [a] or [b]. */
    private fun mul(o: LongArray, a: LongArray, b: LongArray, t: LongArray) {
        t.fill(0)
        for (i in 0 until 16) {
            val ai = a[i]
            for (j in 0 until 16) t[i + j] += ai * b[j]
        }
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        System.arraycopy(t, 0, o, 0, 16)
        carry(o)
        carry(o)
    }

    /** o = z^(p − 2) = 1/z. Every bit of p − 2 from 254 down is set except bits 4 and 2. */
    private fun invert(o: LongArray, z: LongArray, t: LongArray) {
        val c = z.copyOf()
        for (bit in 253 downTo 0) {
            mul(c, c, c, t)
            if (bit != 2 && bit != 4) mul(c, c, z, t)
        }
        System.arraycopy(c, 0, o, 0, 16)
    }
}
