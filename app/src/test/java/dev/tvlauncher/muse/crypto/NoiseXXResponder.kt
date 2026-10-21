package dev.tvlauncher.muse.crypto

/**
 * Test-only XX responder, a port of upstream's `NoiseXXResponder`, so tests can play the
 * Muse VM: [readMessage1AndWriteMessage2], [readMessage3], then [split], which sends with
 * k2 and receives with k1. Keys are drawn from [newKeyPair] ephemeral first, then static.
 */
class NoiseXXResponder(
    private val payload: ByteArray = ByteArray(0),
    private val newKeyPair: () -> X25519KeyPair = { X25519.generateKeyPair() },
) {
    private enum class Phase { START, SENT_2, READ_3, SPLIT, DEAD }

    private val state = SymmetricState()
    private var phase = Phase.START
    private lateinit var e: X25519KeyPair

    val handshakeHash: ByteArray get() = state.h.copyOf()

    /** `-> e` then `<- e, ee, s, es` with [payload]. */
    fun readMessage1AndWriteMessage2(message: ByteArray): ByteArray =
        step(Phase.START, Phase.SENT_2, "readMessage1AndWriteMessage2") {
            if (message.size < DH_LEN) throw NoiseException("message 1 too short (${message.size} < $DH_LEN)")
            val re = message.copyOfRange(0, DH_LEN)
            state.mixHash(re)
            state.decryptAndHash(message.copyOfRange(DH_LEN, message.size))
            e = newKeyPair()
            state.mixHash(e.publicKey)
            state.mixDh(e, re) // ee
            val s = newKeyPair()
            val encryptedStatic = state.encryptAndHash(s.publicKey)
            state.mixDh(s, re) // es
            e.publicKey + encryptedStatic + state.encryptAndHash(payload)
        }

    /** `-> s, se` with an empty payload. */
    fun readMessage3(message: ByteArray) {
        step(Phase.SENT_2, Phase.READ_3, "readMessage3") {
            if (message.size < MIN_MSG3_LEN) throw NoiseException("message 3 too short (${message.size} < $MIN_MSG3_LEN)")
            val rs = state.decryptAndHash(message.copyOfRange(0, DH_LEN + TAG_LEN))
            state.mixDh(e, rs) // se
            state.decryptAndHash(message.copyOfRange(DH_LEN + TAG_LEN, message.size))
        }
    }

    fun split(): NoiseTransport = step(Phase.READ_3, Phase.SPLIT, "split") {
        val (initiatorToResponder, responderToInitiator) = state.split()
        NoiseTransport(sender = responderToInitiator, receiver = initiatorToResponder)
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
        const val MIN_MSG3_LEN = DH_LEN + TAG_LEN + TAG_LEN
    }
}
