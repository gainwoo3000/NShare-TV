package com.nshare.protocol

import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * TLS 1.3 (TLS 1.2 with ECDHE + AEAD on Android 8/9, which lack 1.3) with mutual authentication
 * between NShare devices.
 *
 * Devices use self-signed certificates, so there is no CA to validate against. Instead the peer
 * certificate must be a valid self-signed certificate, and its SHA-256 fingerprint is pinned by the
 * application: [PinningTrustManager] asks a policy whether a fingerprint is acceptable at the TLS
 * level (e.g. the phone refuses a TV whose key changed), and the handshake layer decides pairing,
 * trust-on-first-use and approval once the peer's identity is known.
 */
object NspTls {
    private val PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")

    /** TLS 1.2 only with forward secrecy and authenticated encryption (device keys are EC P-256). */
    private val TLS12_SUITES = setOf(
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
    )

    fun context(keyManagers: Array<KeyManager>, trust: PinningTrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(trust), SecureRandom()) }

    private fun SSLSocket.restrict() {
        enabledProtocols = supportedProtocols.filter { it in PROTOCOLS }.toTypedArray()
        enabledCipherSuites = supportedCipherSuites.filter {
            it.startsWith("TLS_AES_") || it.startsWith("TLS_CHACHA20_") || it in TLS12_SUITES // TLS 1.3 suites are all AEAD
        }.toTypedArray()
    }

    /** Wraps an accepted TCP socket as the TLS server; the client must present a certificate. */
    fun server(ctx: SSLContext, plain: Socket): SSLSocket =
        (ctx.socketFactory.createSocket(plain, plain.inetAddress.hostAddress, plain.port, true) as SSLSocket).apply {
            useClientMode = false
            needClientAuth = true
            restrict()
        }

    /** Wraps a connected TCP socket as the TLS client. */
    fun client(ctx: SSLContext, plain: Socket): SSLSocket =
        (ctx.socketFactory.createSocket(plain, plain.inetAddress.hostAddress, plain.port, true) as SSLSocket).apply {
            useClientMode = true
            restrict()
        }

    fun fingerprint(cert: X509Certificate): ByteArray = MessageDigest.getInstance("SHA-256").digest(cert.encoded)

    fun peerFingerprint(socket: SSLSocket): ByteArray =
        fingerprint(socket.session.peerCertificates[0] as X509Certificate)

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Short form advertised over mDNS (first 8 bytes) so senders can tell a TV they already know. */
    fun shortFingerprint(fp: ByteArray): String = hex(fp.copyOf(8))
}

/**
 * Accepts only well-formed self-signed certificates whose fingerprint [accept] approves.
 * Used for both directions (client certificates on the TV, the TV certificate on the phone).
 */
class PinningTrustManager(private val accept: (fingerprintHex: String) -> Boolean) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun check(chain: Array<out X509Certificate>?) {
        val cert = chain?.firstOrNull() ?: throw CertificateException("no peer certificate")
        try {
            cert.checkValidity()
            cert.verify(cert.publicKey) // must be self-signed by the key it certifies
        } catch (e: Exception) {
            throw CertificateException("invalid device certificate: ${e.message}", e)
        }
        val fp = NspTls.hex(NspTls.fingerprint(cert))
        if (!accept(fp)) throw CertificateException("device certificate $fp is not accepted")
    }
}
