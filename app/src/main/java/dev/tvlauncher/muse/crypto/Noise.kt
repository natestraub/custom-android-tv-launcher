package dev.tvlauncher.muse.crypto

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A Noise handshake or transport failure. Whatever threw it can't be used again. */
class NoiseException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/**
 * Post-handshake Noise transport (docs/muse-protocol.md §3.4.2–3): each WebSocket message is
 * one AES-GCM record with empty AD and a per-direction counter from 0. [encrypt] spends
 * the next send nonce, so encrypt and socket writes must happen in the same order, under
 * one lock. Any failure in either direction kills the transport; reconnect and redo the
 * handshake.
 */
class NoiseTransport internal constructor(
    private val sender: CipherState,
    private val receiver: CipherState,
) {
    @Volatile private var dead = false

    fun encrypt(plaintext: ByteArray): ByteArray = guarded { sender.encryptWithAd(NO_AD, plaintext) }

    fun decrypt(ciphertext: ByteArray): ByteArray = guarded { receiver.decryptWithAd(NO_AD, ciphertext) }

    private inline fun guarded(block: () -> ByteArray): ByteArray {
        if (dead) throw NoiseException("transport is dead after an earlier failure")
        try {
            return block()
        } catch (failure: Exception) {
            dead = true
            throw failure
        }
    }

    private companion object {
        val NO_AD = ByteArray(0)
    }
}

/**
 * Noise CipherState (spec rev 34 §5.1) for AESGCM: AES-256-GCM with a 16-byte tag and
 * nonce = 4 zero bytes ‖ u64be(n). Like upstream it stops short of [MAX_SAFE_NONCE], and
 * any failure poisons it for good, so a bad record can't be skipped or retried.
 */
internal class CipherState(key: ByteArray, private var nonce: Long = 0) {
    init {
        require(key.size == KEY_LEN) { "AES-256-GCM key must be $KEY_LEN bytes" }
    }

    private val key = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var poisoned = false

    @Synchronized
    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray = guarded {
        prepare(Cipher.ENCRYPT_MODE, ad).doFinal(plaintext)
    }

    @Synchronized
    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray = guarded {
        if (ciphertext.size < TAG_LEN) throw NoiseException("ciphertext shorter than the GCM tag")
        val gcm = prepare(Cipher.DECRYPT_MODE, ad)
        try {
            gcm.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw NoiseException("decrypt failed", e)
        }
    }

    /** Spends the next nonce. Re-inits every time: GCM refuses to reuse an encrypt-mode IV. */
    private fun prepare(mode: Int, ad: ByteArray): Cipher {
        if (nonce >= MAX_SAFE_NONCE) throw NoiseException("nonce exhausted")
        val iv = ByteBuffer.allocate(12).putInt(0).putLong(nonce++).array()
        cipher.init(mode, key, GCMParameterSpec(TAG_LEN * 8, iv))
        if (ad.isNotEmpty()) cipher.updateAAD(ad)
        return cipher
    }

    private inline fun guarded(block: () -> ByteArray): ByteArray {
        if (poisoned) throw NoiseException("cipher state poisoned by an earlier failure")
        try {
            return block()
        } catch (failure: Exception) {
            poisoned = true
            throw failure
        }
    }

    companion object {
        const val KEY_LEN = 32
        const val TAG_LEN = 16

        /** Upstream's cap (JavaScript's largest safe integer), far below GCM's 2^64. */
        const val MAX_SAFE_NONCE = (1L shl 53) - 1
    }
}

/**
 * Noise SymmetricState (spec rev 34 §5.2) for Noise_XX_25519_AESGCM_SHA256 with an empty
 * prologue. Shared by the initiator and the test responder.
 */
internal class SymmetricState {
    /** The handshake hash, also the AD for handshake payloads. Kept after [split]. */
    var h: ByteArray = PROTOCOL_NAME.copyOf(HASH_LEN) // names up to 32 bytes are zero-padded, not hashed
        private set
    private var ck = h.copyOf()
    private var cipher: CipherState? = null

    init {
        mixHash(ByteArray(0)) // the empty prologue
    }

    fun mixHash(data: ByteArray) {
        h = MessageDigest.getInstance("SHA-256").run {
            update(h)
            digest(data)
        }
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val (nextCk, key) = Hkdf.noise2(ck, inputKeyMaterial)
        ck = nextCk
        cipher = CipherState(key)
    }

    /** MixKey(DH(keyPair, publicKey)). A low-order peer key fails like any other handshake error. */
    fun mixDh(keyPair: X25519KeyPair, publicKey: ByteArray) {
        val shared = try {
            X25519.sharedSecret(keyPair.privateKey, publicKey)
        } catch (e: LowOrderPointException) {
            throw NoiseException("X25519: ${e.message}", e)
        }
        mixKey(shared)
    }

    /** Before the first MixKey there is no key yet, so data passes through in the clear. */
    fun encryptAndHash(plaintext: ByteArray): ByteArray =
        (cipher?.encryptWithAd(h, plaintext) ?: plaintext).also { mixHash(it) }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray =
        (cipher?.decryptWithAd(h, ciphertext) ?: ciphertext).also { mixHash(ciphertext) }

    /** The transport ciphers: the initiator sends with the first and receives with the second. */
    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = Hkdf.noise2(ck, ByteArray(0))
        ck = ByteArray(HASH_LEN)
        cipher = null
        return CipherState(k1) to CipherState(k2)
    }

    private companion object {
        const val HASH_LEN = 32
        val PROTOCOL_NAME = "Noise_XX_25519_AESGCM_SHA256".toByteArray(Charsets.US_ASCII)
    }
}
