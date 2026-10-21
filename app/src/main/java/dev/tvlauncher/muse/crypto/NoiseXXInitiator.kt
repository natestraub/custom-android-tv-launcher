package dev.tvlauncher.muse.crypto

/**
 * The device's side of the Muse link handshake: Noise_XX_25519_AESGCM_SHA256 (Noise spec
 * rev 34) with an empty prologue, device = initiator (docs/muse-protocol.md §3.4.2). A port
 * of upstream's `NoiseXXInitiator`.
 *
 * The device has no long-term Noise identity: its static key is fresh for every handshake,
 * because the bearer token already authenticated it at the WebSocket upgrade. The VM's
 * static key isn't pinned either; TLS is what authenticates the server.
 *
 * Call [writeMessage1], [readMessage2], [writeMessage3], then [split]. Everything throws
 * [NoiseException]: a failed step kills the handshake, an out-of-order call doesn't.
 */
class NoiseXXInitiator internal constructor(private val newKeyPair: () -> X25519KeyPair) {
    /** Production: ephemeral and static keys from [java.security.SecureRandom]. */
    constructor() : this({ X25519.generateKeyPair() })

    private enum class Phase { START, SENT_1, READ_2, SENT_3, SPLIT, DEAD }

    private val state = SymmetricState()
    private var phase = Phase.START
    private lateinit var e: X25519KeyPair
    private lateinit var re: ByteArray

    /** The transcript hash `h`, final once message 3 is written. */
    val handshakeHash: ByteArray get() = state.h.copyOf()

    /** `-> e`: just the 32-byte ephemeral key, since the empty payload has no key to encrypt it. */
    fun writeMessage1(): ByteArray = step(Phase.START, Phase.SENT_1, "writeMessage1") {
        e = newKeyPair()
        state.mixHash(e.publicKey)
        state.encryptAndHash(ByteArray(0))
        e.publicKey.copyOf()
    }

    /** `<- e, ee, s, es` and a payload, which is authenticated and then dropped. */
    fun readMessage2(message: ByteArray) {
        step(Phase.SENT_1, Phase.READ_2, "readMessage2") {
            if (message.size < MIN_MSG2_LEN) throw NoiseException("message 2 too short (${message.size} < $MIN_MSG2_LEN)")
            re = message.copyOfRange(0, DH_LEN)
            state.mixHash(re)
            state.mixDh(e, re) // ee
            val rs = state.decryptAndHash(message.copyOfRange(DH_LEN, 2 * DH_LEN + TAG_LEN))
            state.mixDh(e, rs) // es
            state.decryptAndHash(message.copyOfRange(2 * DH_LEN + TAG_LEN, message.size))
        }
    }

    /** `-> s, se` with an empty payload: 64 bytes. */
    fun writeMessage3(): ByteArray = step(Phase.READ_2, Phase.SENT_3, "writeMessage3") {
        val s = newKeyPair()
        val encryptedStatic = state.encryptAndHash(s.publicKey)
        state.mixDh(s, re) // se
        encryptedStatic + state.encryptAndHash(ByteArray(0))
    }

    /** Ends the handshake. The transport sends with k1 and receives with k2. */
    fun split(): NoiseTransport = step(Phase.SENT_3, Phase.SPLIT, "split") {
        val (send, receive) = state.split()
        NoiseTransport(send, receive)
    }

    private inline fun <T> step(from: Phase, to: Phase, name: String, block: () -> T): T {
        if (phase == Phase.DEAD) throw NoiseException("$name called on a dead handshake")
        if (phase != from) throw NoiseException("$name called out of order ($phase)")
        try {
            return block().also { phase = to }
        } catch (failure: Exception) {
            phase = Phase.DEAD
            throw failure
        }
    }

    private companion object {
        const val DH_LEN = X25519.KEY_LEN
        const val TAG_LEN = CipherState.TAG_LEN

        /** e + enc(s) + enc(payload), the payload possibly empty. */
        const val MIN_MSG2_LEN = DH_LEN + (DH_LEN + TAG_LEN) + TAG_LEN
    }
}
