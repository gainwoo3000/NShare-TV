package com.nshare.protocol

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertArrayEquals
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

    private class TvPolicy(override val pinPairing: Boolean, val approveResult: Boolean = true, val mirroring: Boolean = false) : ServerPolicy {
        val trusted = mutableSetOf<String>()
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

    private fun connect(tvPolicy: TvPolicy, phonePolicy: PhonePolicy, phoneVersion: Int = Nsp.VERSION, pinFromTv: Boolean = false): Result {
        val pool = Executors.newFixedThreadPool(2)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { ss ->
            val serverCtx = NspTls.context(tv.keyManagers, PinningTrustManager { true })
            val server = pool.submit<Pair<Hello?, Exception?>> {
                ss.accept().use { plain ->
                    val s = NspTls.server(serverCtx, plain)
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
                        assertEquals(NspTls.PROTOCOL, s.session.protocol)
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
    fun pinProofsBindBothDirections() {
        val n1 = PinProof.newNonce()
        val n2 = PinProof.newNonce()
        val c = PinProof.client("123456", tv.fp, phone.fp, n1, n2)
        val s = PinProof.server("123456", tv.fp, phone.fp, n1, n2)
        assertFalse(PinProof.matches(c, s))
        assertArrayEquals(c, PinProof.client("123456", tv.fp, phone.fp, n1, n2))
        assertFalse(PinProof.matches(c, PinProof.client("123457", tv.fp, phone.fp, n1, n2)))
    }
}
