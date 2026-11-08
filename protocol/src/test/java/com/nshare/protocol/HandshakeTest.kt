package com.nshare.protocol

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLSocket

/** End-to-end v2 handshakes over real TLS 1.3 sockets on localhost. */
class HandshakeTest {
    private class Identity(cn: String) {
        val keyManagers: Array<javax.net.ssl.KeyManager>
        val cert: X509Certificate

        init {
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val name = X500Name("CN=$cn")
            val holder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(System.nanoTime()), Date(System.currentTimeMillis() - 86_400_000),
                Date(System.currentTimeMillis() + 86_400_000L * 365), name, kp.public,
            ).build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private))
            cert = JcaX509CertificateConverter().getCertificate(holder)
            val ks = KeyStore.getInstance("PKCS12").apply {
                load(null)
                setKeyEntry("id", kp.private, CharArray(0), arrayOf(cert))
            }
            keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }.keyManagers
        }

        val fp get() = NspTls.fingerprint(cert)
    }

    private class TvPolicy(
        override val pinPairing: Boolean,
        val approveResult: Boolean = true,
        val mirroring: Boolean = false,
        val pairingFree: Boolean = true,
    ) : ServerPolicy {
        val trusted = mutableSetOf<String>()
        var pairingEnded = false
        override fun beginPairing() = pairingFree
        override fun endPairing() { pairingEnded = true }
        @Volatile var shownPin: String? = null
        override fun isTrusted(fingerprintHex: String) = fingerprintHex in trusted
        override fun trust(fingerprintHex: String, deviceName: String) { trusted += fingerprintHex }
        override fun showPin(pin: String, deviceName: String) { shownPin = pin }
        override fun hidePin() {}
        override fun needsApproval() = mirroring
        override fun approve(deviceName: String, fingerprintHex: String) = approveResult
    }

    private class PhonePolicy(val pins: List<String?>) : ClientPolicy {
        var asked = 0
        var waited = false
        var trustedTv: String? = null
        override fun requestPin(retry: Boolean): String? = pins.getOrNull(asked++)
        override fun onWaitingForApproval() { waited = true }
        override fun trustReceiver(fingerprintHex: String) { trustedTv = fingerprintHex }
    }

    private class Result(val caps: Caps?, val clientError: Exception?, val hello: Hello?, val serverError: Exception?)

    private val tv = Identity("NShare TV")
    private val phone = Identity("NShare Phone")
    private val caps = Caps("NShare-TEST", 3840, 2160, 60, hevc = true)

    private fun connect(
        tvPolicy: TvPolicy,
        phonePolicy: PhonePolicy,
        phoneVersion: Int = Nsp.VERSION,
        pinFromTv: Boolean = false,
        tls12Only: Boolean = false,
        peek: Boolean = false,
    ): Result {
        val pool = Executors.newFixedThreadPool(2)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { ss ->
            val serverCtx = NspTls.context(tv.keyManagers, PinningTrustManager { true })
            val server = pool.submit<Pair<Hello?, Exception?>> {
                ss.accept().use { plain ->
                    val s = if (peek) {
                        // Like the TV: read the first bytes to recognise a v1 sender, then start TLS.
                        val first = ByteArray(2).also { DataInputStream(plain.getInputStream()).readFully(it) }
                        NspTls.server(serverCtx, plain, first)
                    } else {
                        NspTls.server(serverCtx, plain)
                    }
                    if (tls12Only) s.enabledProtocols = arrayOf("TLSv1.2") // like an Android 8/9 TV
                    try {
                        s.startHandshake()
                        val hs = ServerHandshake(reader(s), writer(s), caps, tvPolicy, tv.fp, NspTls.peerFingerprint(s))
                        hs.run() to null
                    } catch (e: Exception) {
                        null to e
                    }
                }
            }
            val clientCtx = NspTls.context(phone.keyManagers, PinningTrustManager { true })
            val client = pool.submit<Pair<Caps?, Exception?>> {
                Socket(InetAddress.getLoopbackAddress(), ss.localPort).use { plain ->
                    val s = NspTls.client(clientCtx, plain)
                    try {
                        s.startHandshake()
                        assertEquals(if (tls12Only) "TLSv1.2" else "TLSv1.3", s.session.protocol)
                        if (tls12Only) assertTrue(s.session.cipherSuite, s.session.cipherSuite.startsWith("TLS_ECDHE_ECDSA_WITH_"))
                        val policy = if (pinFromTv) object : ClientPolicy by phonePolicy {
                            override fun requestPin(retry: Boolean): String? {
                                phonePolicy.asked++
                                if (retry) return null
                                while (tvPolicy.shownPin == null) Thread.sleep(5)
                                return tvPolicy.shownPin
                            }
                        } else phonePolicy
                        val hs = ClientHandshake(reader(s), writer(s), Hello("Phone", "Test", 1234, phoneVersion), policy, phone.fp, NspTls.peerFingerprint(s))
                        hs.run() to null
                    } catch (e: Exception) {
                        null to e
                    }
                }
            }
            val c = client.get(10, TimeUnit.SECONDS)
            val sv = server.get(10, TimeUnit.SECONDS)
            pool.shutdownNow()
            return Result(c.first, c.second, sv.first, sv.second)
        }
    }

    private fun reader(s: SSLSocket) = FrameReader(DataInputStream(BufferedInputStream(s.inputStream)))
    private fun writer(s: SSLSocket) = FrameWriter(DataOutputStream(BufferedOutputStream(s.outputStream)))

    @Test
    fun trustOnFirstUseWithoutPin() {
        val tvPolicy = TvPolicy(pinPairing = false)
        val r = connect(tvPolicy, PhonePolicy(emptyList()))
        assertEquals(3840, r.caps!!.maxWidth)
        assertEquals("Phone", r.hello!!.deviceName)
        assertTrue(NspTls.hex(phone.fp) in tvPolicy.trusted)
    }

    @Test
    fun pinPairingWithCorrectPin() {
        val tvPolicy = TvPolicy(pinPairing = true)
        val phonePolicy = PhonePolicy(emptyList())
        val r = connect(tvPolicy, phonePolicy, pinFromTv = true)
        assertTrue("client: ${r.clientError}", r.caps != null)
        assertTrue(NspTls.hex(phone.fp) in tvPolicy.trusted)
        assertEquals(1, phonePolicy.asked)
    }

    @Test
    fun wrongPinCanBeRetriedThenCancelled() {
        val tvPolicy = TvPolicy(pinPairing = true)
        val phonePolicy = PhonePolicy(listOf("000000", null))
        val r = connect(tvPolicy, phonePolicy)
        // First attempt is wrong (asked again with retry=true), the user then cancels.
        assertEquals(2, phonePolicy.asked)
        assertEquals(Nsp.Error.PAIRING, (r.clientError as NspRefusedException).code)
        assertFalse(NspTls.hex(phone.fp) in tvPolicy.trusted)
    }

    @Test
    fun trustedDeviceSkipsPairing() {
        val tvPolicy = TvPolicy(pinPairing = true).apply { trusted += NspTls.hex(phone.fp) }
        val phonePolicy = PhonePolicy(emptyList())
        val r = connect(tvPolicy, phonePolicy)
        assertTrue(r.caps != null)
        assertEquals(0, phonePolicy.asked)
    }

    @Test
    fun declinedSecondSender() {
        val phonePolicy = PhonePolicy(emptyList())
        val r = connect(TvPolicy(pinPairing = false, approveResult = false, mirroring = true), phonePolicy)
        assertTrue(phonePolicy.waited)
        assertEquals(Nsp.Error.REJECTED, (r.clientError as NspRefusedException).code)
    }

    @Test
    fun versionMismatchIsRefusedOnBothSides() {
        val r = connect(TvPolicy(pinPairing = false), PhonePolicy(emptyList()), phoneVersion = 1)
        assertEquals(Nsp.Error.VERSION, (r.clientError as NspRefusedException).code)
        assertEquals(Nsp.Error.VERSION, (r.serverError as NspRefusedException).code)
    }

    @Test
    fun pinningTrustManagerRejectsUnapprovedCertificate() {
        val tm = PinningTrustManager { it == "nope" }
        try {
            tm.checkServerTrusted(arrayOf(tv.cert), "EC")
            throw AssertionError("expected CertificateException")
        } catch (_: java.security.cert.CertificateException) {
        }
        PinningTrustManager { it == NspTls.hex(tv.fp) }.checkServerTrusted(arrayOf(tv.cert), "EC")
    }

    @Test
    fun declinedDeviceIsNotTrusted() {
        // Trust on first use, but the TV user declines the takeover: the device must not be remembered.
        val tvPolicy = TvPolicy(pinPairing = false, approveResult = false, mirroring = true)
        val r = connect(tvPolicy, PhonePolicy(emptyList()))
        assertEquals(Nsp.Error.REJECTED, (r.clientError as NspRefusedException).code)
        assertFalse(NspTls.hex(phone.fp) in tvPolicy.trusted)
    }

    @Test
    fun pairedButDeclinedIsTrustedOnNeitherSide() {
        val tvPolicy = TvPolicy(pinPairing = true, approveResult = false, mirroring = true)
        val phonePolicy = PhonePolicy(emptyList())
        val r = connect(tvPolicy, phonePolicy, pinFromTv = true)
        assertEquals(Nsp.Error.REJECTED, (r.clientError as NspRefusedException).code)
        assertFalse(NspTls.hex(phone.fp) in tvPolicy.trusted)
        assertEquals(null, phonePolicy.trustedTv)
    }

    @Test
    fun secondPairingIsRefusedWhileOneIsInProgress() {
        val tvPolicy = TvPolicy(pinPairing = true, pairingFree = false)
        val r = connect(tvPolicy, PhonePolicy(listOf("123456")))
        assertEquals(Nsp.Error.BUSY, (r.clientError as NspRefusedException).code)
        assertFalse("must not release a slot it never took", tvPolicy.pairingEnded)
    }

    @Test
    fun pairingReleasesTheSlot() {
        val tvPolicy = TvPolicy(pinPairing = true)
        connect(tvPolicy, PhonePolicy(emptyList()), pinFromTv = true)
        assertTrue(tvPolicy.pairingEnded)
    }

    @Test
    fun tlsAfterPeekingAtTheFirstBytes() {
        val r = connect(TvPolicy(pinPairing = false), PhonePolicy(emptyList()), peek = true)
        assertTrue("client: ${r.clientError} server: ${r.serverError}", r.caps != null)
    }

    @Test
    fun tls12FallbackForOldTvs() {
        val r = connect(TvPolicy(pinPairing = false), PhonePolicy(emptyList()), tls12Only = true)
        assertTrue("client: ${r.clientError}", r.caps != null)
    }

    @Test
    fun srpAcceptsTheRightPinOnly() {
        val id = Srp.identity(tv.fp, phone.fp)
        val server = Srp.Server(id, "123456")
        val good = Srp.Client(id, "123456")
        val m2 = server.verify(good.publicKey, good.proof(server.salt, server.publicKey)!!)
        assertTrue(m2 != null && good.verifyServer(m2))

        val server2 = Srp.Server(id, "123456")
        val bad = Srp.Client(id, "123457")
        assertEquals(null, server2.verify(bad.publicKey, bad.proof(server2.salt, server2.publicKey)!!))
    }

    @Test
    fun srpFailsThroughARelayingMiddleman() {
        // A man in the middle terminates TLS with its own certificate on each side and relays the
        // pairing messages. The user types the right PIN, yet the TV sees (tv, mitm) and the phone
        // sees (mitm, phone), so the identities differ and no proof matches.
        val mitm = Identity("Attacker")
        val server = Srp.Server(Srp.identity(tv.fp, mitm.fp), "123456")
        val client = Srp.Client(Srp.identity(mitm.fp, phone.fp), "123456")
        val m1 = client.proof(server.salt, server.publicKey)!!
        assertEquals(null, server.verify(client.publicKey, m1))
    }

    @Test
    fun srpRejectsDegeneratePublicValues() {
        val id = Srp.identity(tv.fp, phone.fp)
        val server = Srp.Server(id, "123456")
        assertEquals(null, server.verify(ByteArray(1), ByteArray(32)))
        assertEquals(null, server.verify(Srp.N.toByteArray(), ByteArray(32)))
        assertEquals(null, Srp.Client(id, "123456").proof(server.salt, ByteArray(1)))
    }
}
