package com.nshare.protocol

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory

/**
 * Opt-in integration test against a real NShare TV receiver (skipped unless NSHARE_TV_HOST is set).
 *
 *   NSHARE_TV_HOST=192.168.0.10 NSHARE_SCENARIO=pin NSHARE_PIN_FILE=/tmp/pin ./gradlew :protocol:testDebugUnitTest --tests '*LiveReceiverTest*'
 *
 * Scenarios: "connect" (trust / TOFU), "pin" (reads the PIN shown on the TV from NSHARE_PIN_FILE),
 * "v1" (announces protocol v1 and expects a version refusal), "drop" (goes silent so the TV's idle
 * timeout fires, then reconnects with the same identity to exercise the TV's resume path). Uses a fresh identity each run unless
 * NSHARE_KEY_FILE points to a PKCS#12 file to reuse.
 */
class LiveReceiverTest {
    @Test
    fun liveHandshake() {
        val host = System.getenv("NSHARE_TV_HOST")
        assumeTrue("NSHARE_TV_HOST not set", !host.isNullOrBlank())
        val scenario = System.getenv("NSHARE_SCENARIO") ?: "connect"
        val (kms, fp) = identity(System.getenv("NSHARE_KEY_FILE"))
        val plain = Socket().apply { connect(InetSocketAddress(host, Nsp.DEFAULT_PORT), 5000) }
        val ssl = NspTls.client(NspTls.context(kms, PinningTrustManager { true }), plain)
        ssl.soTimeout = 130_000
        ssl.startHandshake()
        println("LIVE: TLS ${ssl.session.protocol} ${ssl.session.cipherSuite}, TV key ${NspTls.shortFingerprint(NspTls.peerFingerprint(ssl))}")
        val reader = FrameReader(DataInputStream(BufferedInputStream(ssl.inputStream)))
        val writer = FrameWriter(DataOutputStream(BufferedOutputStream(ssl.outputStream)))
        val policy = object : ClientPolicy {
            override fun requestPin(retry: Boolean): String? {
                val f = File(System.getenv("NSHARE_PIN_FILE") ?: return null)
                println("LIVE: PIN requested (retry=$retry), waiting for ${f.path}")
                f.delete()
                val until = System.currentTimeMillis() + 120_000
                while (System.currentTimeMillis() < until) {
                    if (f.exists()) return f.readText().trim().also { println("LIVE: sending PIN $it") }
                    Thread.sleep(200)
                }
                return null
            }
            override fun onWaitingForApproval() = println("LIVE: waiting for approval on the TV")
            override fun trustReceiver(fingerprintHex: String) = println("LIVE: TV proved the PIN, trusting ${fingerprintHex.take(16)}")
        }
        val version = if (scenario == "v1") 1 else Nsp.VERSION
        try {
            val caps = ClientHandshake(reader, writer, Hello("Mac test client", "JVM", 9, version), policy, fp, NspTls.peerFingerprint(ssl)).run()
            println("LIVE: CAPS ${caps.receiverName} ${caps.maxWidth}x${caps.maxHeight} hevc=${caps.hevc}")
            if (scenario == "drop") {
                println("LIVE: going silent (no PING) for 7 s")
                Thread.sleep(7_000)
                return
            }
            writer.write(Nsp.Type.BYE)
        } catch (e: NspRefusedException) {
            println("LIVE: refused code=${e.code} ${e.message}")
        } finally {
            ssl.close()
        }
    }

    private fun identity(path: String?): Pair<Array<javax.net.ssl.KeyManager>, ByteArray> {
        val ks = KeyStore.getInstance("PKCS12")
        val file = path?.let { File(it) }
        if (file != null && file.exists()) {
            file.inputStream().use { ks.load(it, CharArray(0)) }
        } else {
            val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val name = X500Name("CN=Mac test client")
            val cert = JcaX509CertificateConverter().getCertificate(
                JcaX509v3CertificateBuilder(name, BigInteger.valueOf(System.nanoTime()), Date(System.currentTimeMillis() - 86_400_000),
                    Date(System.currentTimeMillis() + 86_400_000L * 365), name, kp.public)
                    .build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)),
            )
            ks.load(null)
            ks.setKeyEntry("id", kp.private, CharArray(0), arrayOf(cert))
            file?.outputStream()?.use { ks.store(it, CharArray(0)) }
        }
        val cert = ks.getCertificate("id") as java.security.cert.X509Certificate
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        return kmf.keyManagers to NspTls.fingerprint(cert)
    }
}
