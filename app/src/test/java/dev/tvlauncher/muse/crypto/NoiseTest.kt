package dev.tvlauncher.muse.crypto

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    // muse/extra_vectors.json › noise_xx: upstream's Python run with fixed keys 11…, 22…, 33…, 44….
    private val vector = JSONObject(javaClass.getResource("/muse/extra_vectors.json")!!.readText())
        .getJSONObject("noise_xx")
    private val msg2Key = "msg2_hex(responder payload empty)"
    private val firstPlaintext = vector.getJSONObject("first_transport_message")
        .getString("plaintext_hex(noise_transport_frame above)")
    private val firstCiphertext = vector.getJSONObject("first_transport_message").getString("ciphertext_hex")
    private val noAd = ByteArray(0)

    private fun bytes(key: String) = hex(vector.getString(key))

    /** Key pairs from the vector's private keys, in draw order (ephemeral, then static). */
    private fun fixedKeys(vararg privateKeys: String): () -> X25519KeyPair {
        val queue = ArrayDeque(privateKeys.map { bytes(it) })
        return { queue.removeFirst().let { X25519KeyPair(it, X25519.publicKey(it)) } }
    }

    private fun vectorInitiator() =
        NoiseXXInitiator(fixedKeys("initiator_ephemeral_private_hex", "initiator_static_private_hex"))

    private fun vectorResponder() =
        NoiseXXResponder(newKeyPair = fixedKeys("responder_ephemeral_private_hex", "responder_static_private_hex"))

    /** A full handshake between fresh parties: (device transport, VM transport). */
    private fun handshake(): Pair<NoiseTransport, NoiseTransport> {
        val initiator = NoiseXXInitiator()
        val responder = NoiseXXResponder()
        initiator.readMessage2(responder.readMessage1AndWriteMessage2(initiator.writeMessage1()))
        responder.readMessage3(initiator.writeMessage3())
        return initiator.split() to responder.split()
    }

    @Test
    fun `initiator reproduces the upstream fixed-key handshake byte for byte`() {
        val initiator = vectorInitiator()
        assertEquals(vector.getString("msg1_hex"), initiator.writeMessage1().hex())
        initiator.readMessage2(bytes(msg2Key))
        assertEquals(vector.getString("msg3_hex"), initiator.writeMessage3().hex())
        assertEquals(vector.getString("handshake_hash_hex"), initiator.handshakeHash.hex())

        val transport = initiator.split()
        assertEquals(firstCiphertext, transport.encrypt(hex(firstPlaintext)).hex())
        // Unlike upstream, the hash stays readable after split (Noise's GetHandshakeHash).
        assertEquals(vector.getString("handshake_hash_hex"), initiator.handshakeHash.hex())
    }

    @Test
    fun `transport sends with k1 and receives with k2 from the final chaining key`() {
        val k1 = bytes("initiator_send_key_hex(k1)")
        val k2 = bytes("initiator_recv_key_hex(k2)")
        val (splitK1, splitK2) = Hkdf.noise2(bytes("final_chaining_key_hex"), ByteArray(0))
        assertArrayEquals(k1, splitK1)
        assertArrayEquals(k2, splitK2)

        val initiator = vectorInitiator()
        initiator.writeMessage1()
        initiator.readMessage2(bytes(msg2Key))
        initiator.writeMessage3()
        val transport = initiator.split()
        assertEquals("ping", String(CipherState(k1).decryptWithAd(noAd, transport.encrypt("ping".toByteArray()))))
        assertEquals("pong", String(transport.decrypt(CipherState(k2).encryptWithAd(noAd, "pong".toByteArray()))))
    }

    @Test
    fun `test responder reproduces message 2 and reads message 3`() {
        val responder = vectorResponder()
        assertEquals(vector.getString(msg2Key), responder.readMessage1AndWriteMessage2(bytes("msg1_hex")).hex())
        responder.readMessage3(bytes("msg3_hex"))
        assertEquals(vector.getString("handshake_hash_hex"), responder.handshakeHash.hex())
        assertEquals(firstPlaintext, responder.split().decrypt(hex(firstCiphertext)).hex())
    }

    @Test
    fun `fresh initiator and responder agree and talk both ways`() {
        val initiator = NoiseXXInitiator()
        val responder = NoiseXXResponder()
        val msg1 = initiator.writeMessage1()
        val msg2 = responder.readMessage1AndWriteMessage2(msg1)
        initiator.readMessage2(msg2)
        val msg3 = initiator.writeMessage3()
        responder.readMessage3(msg3)
        assertEquals(listOf(32, 96, 64), listOf(msg1.size, msg2.size, msg3.size))
        assertArrayEquals(initiator.handshakeHash, responder.handshakeHash)

        val device = initiator.split()
        val vm = responder.split()
        repeat(3) { i ->
            assertEquals("frame $i", String(vm.decrypt(device.encrypt("frame $i".toByteArray()))))
            assertEquals("reply $i", String(device.decrypt(vm.encrypt("reply $i".toByteArray()))))
        }
        // Every record spends a nonce, so the same plaintext never repeats on the wire.
        assertFalse(device.encrypt(byteArrayOf(1)).contentEquals(device.encrypt(byteArrayOf(1))))
    }

    @Test
    fun `a payload in message 2 is authenticated and discarded`() {
        val initiator = NoiseXXInitiator()
        val responder = NoiseXXResponder(payload = "hello".toByteArray())
        val msg2 = responder.readMessage1AndWriteMessage2(initiator.writeMessage1())
        assertEquals(96 + 5, msg2.size)
        initiator.readMessage2(msg2)
        responder.readMessage3(initiator.writeMessage3())
        assertArrayEquals(initiator.handshakeHash, responder.handshakeHash)
        assertEquals("ok", String(responder.split().decrypt(initiator.split().encrypt("ok".toByteArray()))))
    }

    @Test
    fun `nonce is four zero bytes then the big-endian counter`() {
        val key = ByteArray(32) { it.toByte() }
        val ad = byteArrayOf(7)
        val expected = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, hex("000000000001020304050607")))
            updateAAD(ad)
            doFinal("hello".toByteArray())
        }
        assertArrayEquals(expected, CipherState(key, nonce = 0x0001020304050607).encryptWithAd(ad, "hello".toByteArray()))
    }

    @Test
    fun `cipher state refuses the max safe nonce`() {
        val key = ByteArray(32)
        val sender = CipherState(key, nonce = CipherState.MAX_SAFE_NONCE - 1)
        val receiver = CipherState(key, nonce = CipherState.MAX_SAFE_NONCE - 1)
        val last = sender.encryptWithAd(noAd, byteArrayOf(1))
        assertArrayEquals(byteArrayOf(1), receiver.decryptWithAd(noAd, last))
        assertThrows(NoiseException::class.java) { sender.encryptWithAd(noAd, byteArrayOf(2)) }
        assertThrows(NoiseException::class.java) { receiver.decryptWithAd(noAd, last) }
    }

    @Test
    fun `cipher state is poisoned by a failed decrypt`() {
        val key = ByteArray(32) { 2 }
        val sender = CipherState(key)
        val receiver = CipherState(key)
        val tampered = sender.encryptWithAd(noAd, byteArrayOf(1)).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(NoiseException::class.java) { receiver.decryptWithAd(noAd, tampered) }
        // The next genuine record carries the matching nonce but is refused all the same.
        val next = sender.encryptWithAd(noAd, byteArrayOf(2))
        assertThrows(NoiseException::class.java) { receiver.decryptWithAd(noAd, next) }
    }

    @Test
    fun `exhausting the send nonce kills the whole transport`() {
        val key = ByteArray(32) { 1 }
        val transport = NoiseTransport(CipherState(key, nonce = CipherState.MAX_SAFE_NONCE - 1), CipherState(key))
        val peer = CipherState(key)
        transport.encrypt(byteArrayOf(1))
        assertThrows(NoiseException::class.java) { transport.encrypt(byteArrayOf(2)) }
        assertThrows(NoiseException::class.java) { transport.decrypt(peer.encryptWithAd(noAd, byteArrayOf(3))) }
    }

    @Test
    fun `tampered record fails and poisons the transport`() {
        val (device, vm) = handshake()
        assertEquals("one", String(device.decrypt(vm.encrypt("one".toByteArray()))))
        val tampered = vm.encrypt("two".toByteArray()).also { it[0] = (it[0].toInt() xor 1).toByte() }
        val failure = assertThrows(NoiseException::class.java) { device.decrypt(tampered) }
        assertTrue(failure.cause is AEADBadTagException)
        // The counters still line up, but a failed record is fatal: no skipping, no retry.
        val three = vm.encrypt("three".toByteArray())
        assertThrows(NoiseException::class.java) { device.decrypt(three) }
        assertThrows(NoiseException::class.java) { device.encrypt("four".toByteArray()) }
    }

    @Test
    fun `record shorter than the tag fails`() {
        val (device, _) = handshake()
        assertThrows(NoiseException::class.java) { device.decrypt(ByteArray(15)) }
    }

    @Test
    fun `tampered message 2 fails and kills the handshake`() {
        for (index in listOf(0, 40, 95)) { // re, enc(s), payload tag
            val initiator = NoiseXXInitiator()
            val msg2 = NoiseXXResponder().readMessage1AndWriteMessage2(initiator.writeMessage1())
            msg2[index] = (msg2[index].toInt() xor 1).toByte()
            assertThrows(NoiseException::class.java) { initiator.readMessage2(msg2) }
            assertThrows(NoiseException::class.java) { initiator.writeMessage3() }
        }
    }

    @Test
    fun `message 2 shorter than 96 bytes is rejected`() {
        for (size in listOf(0, 32, 95)) {
            val initiator = NoiseXXInitiator()
            val msg2 = NoiseXXResponder().readMessage1AndWriteMessage2(initiator.writeMessage1())
            val failure = assertThrows(NoiseException::class.java) { initiator.readMessage2(msg2.copyOf(size)) }
            assertTrue(failure.message, failure.message!!.contains("too short"))
            // The handshake is dead now: even the genuine message is refused.
            assertThrows(NoiseException::class.java) { initiator.readMessage2(msg2) }
        }
    }

    @Test
    fun `low-order responder ephemeral is rejected`() {
        val initiator = NoiseXXInitiator()
        initiator.writeMessage1()
        val failure = assertThrows(NoiseException::class.java) { initiator.readMessage2(ByteArray(96)) }
        assertTrue(failure.cause is LowOrderPointException)
    }

    @Test
    fun `out-of-order calls are refused without killing the handshake`() {
        val initiator = NoiseXXInitiator()
        val responder = NoiseXXResponder()
        assertThrows(NoiseException::class.java) { initiator.writeMessage3() }
        assertThrows(NoiseException::class.java) { initiator.split() }
        val msg1 = initiator.writeMessage1()
        assertThrows(NoiseException::class.java) { initiator.writeMessage1() }
        initiator.readMessage2(responder.readMessage1AndWriteMessage2(msg1))
        responder.readMessage3(initiator.writeMessage3())
        val device = initiator.split()
        assertThrows(NoiseException::class.java) { initiator.split() }
        assertEquals("fine", String(responder.split().decrypt(device.encrypt("fine".toByteArray()))))
    }
}
