package dev.tvlauncher.muse.net

import dev.tvlauncher.muse.net.WebSocketClient.Message
import dev.tvlauncher.muse.net.WebSocketClient.Options
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit.SECONDS
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/** wss:// against a local TLS server with a self-signed certificate for localhost and 127.0.0.1. */
class WebSocketTlsTest {
    private val cert = CertificateFactory.getInstance("X.509")
        .generateCertificate(Base64.getDecoder().decode(CERT).inputStream())
    private val serverTls = SSLContext.getInstance("TLS").apply {
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(KEY)))
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("ws", key, PASSWORD, arrayOf(cert))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, PASSWORD) }
        init(managers.keyManagers, null, null)
    }
    private val clientTls = SSLContext.getInstance("TLS").apply {
        val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ws", cert)
        }
        init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trusted) }.trustManagers, null)
    }
    private val server = FakeWebSocketServer(
        serverTls.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()),
    )

    @After
    fun tearDown() = server.close()

    private fun options(verifier: HostnameVerifier) =
        Options(closeTimeoutMs = 200, sslSocketFactory = clientTls.socketFactory, hostnameVerifier = verifier)

    private fun Peer.sniNames() = ((socket as SSLSocket).session as ExtendedSSLSession).requestedServerNames
        .map { (it as SNIHostName).asciiName }

    @Test
    fun `wss sends SNI, verifies the hostname and carries frames`() {
        val verified = mutableListOf<String>()
        val accepted = server.accept()
        val ws = WebSocketClient.connect(
            server.url("/v1/noise", scheme = "wss", host = "localhost"),
            options = options { host, session ->
                verified += host
                session.peerCertificates.isNotEmpty()
            },
        )
        val peer = accepted.get(5, SECONDS)
        assertEquals(listOf("localhost"), verified)
        assertEquals(listOf("localhost"), peer.sniNames())
        assertEquals("localhost:${server.port}", peer.headers["host"])
        ws.sendBinary(byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), peer.readFrame().payload)
        peer.sendFrame(2, byteArrayOf(3))
        assertArrayEquals(byteArrayOf(3), (ws.receive(5_000) as Message.Binary).data)
        ws.close()
    }

    @Test
    fun `wss refuses a certificate the hostname verifier rejects`() {
        server.accept()
        val refused = assertThrows(SSLPeerUnverifiedException::class.java) {
            WebSocketClient.connect(server.url(scheme = "wss", host = "localhost"), options = options { _, _ -> false })
        }
        assertTrue(refused.message!!.contains("localhost"))
    }

    @Test
    fun `an IP address is verified but never sent as SNI`() {
        val verified = mutableListOf<String>()
        val accepted = server.accept()
        val ws = WebSocketClient.connect(server.url(scheme = "wss"), options = options { host, _ -> verified += host; true })
        assertEquals(listOf("127.0.0.1"), verified)
        assertEquals(emptyList<String>(), accepted.get(5, SECONDS).sniNames())
        ws.close()
    }

    private companion object {
        val PASSWORD = "test".toCharArray()

        // keytool -genkeypair -keyalg EC -groupname secp256r1 -dname CN=localhost
        //   -ext SAN=dns:localhost,ip:127.0.0.1 -validity 36500 (valid until 2126)
        const val KEY = "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCCiEXg6HQi2zD/7bBOZhsk5P0Ds0iBH3Vfoo6zMyfvbUQ=="
        const val CERT =
            "MIIBXTCCAQOgAwIBAgIIbWpzq6N+GF8wCgYIKoZIzj0EAwIwFDESMBAGA1UEAxMJbG9jYWxob3N0MCAXDTI2MTAyMDE4MjAy" +
                "MloYDzIxMjYwOTI2MTgyMDIyWjAUMRIwEAYDVQQDEwlsb2NhbGhvc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQCRnnb" +
                "Wd07DPo5TPpLfgoudcJi8RVg810J8552O00/mcWZrpYvcbIYyBOfUZviZQedcyWgkg6b99Ao0GzGKSWvoz0wOzAdBgNVHQ4E" +
                "FgQUkWOXf5PoyUnsIdSefcCtig7YatgwGgYDVR0RBBMwEYIJbG9jYWxob3N0hwR/AAABMAoGCCqGSM49BAMCA0gAMEUCIQD6" +
                "Q2I3FuoH7B5wumU4oQeCG13MXjQf2vN60BWswTOIQAIgbgB4LYGPEfl8iRFUED+9Ba2zia2nO9hi8PzKxykIoqA="
    }
}
