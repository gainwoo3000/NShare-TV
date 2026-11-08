package com.nshare.protocol

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * This device's TLS identity: an EC P-256 key generated once in AndroidKeyStore (never leaves the
 * secure hardware) with the self-signed certificate the keystore creates for it.
 */
class DeviceKey private constructor(private val alias: String) {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    val certificate: X509Certificate get() = keyStore.getCertificate(alias) as X509Certificate
    val fingerprint: ByteArray get() = NspTls.fingerprint(certificate)

    /** Key manager presenting this identity as both TLS client and server. */
    fun keyManagers(): Array<KeyManager> = arrayOf(object : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = alias
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
        override fun getCertificateChain(a: String?): Array<X509Certificate> = arrayOf(certificate)
        override fun getPrivateKey(a: String?): PrivateKey = keyStore.getKey(alias, null) as PrivateKey
    })

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Loads the identity stored under [alias], creating it on first use. */
        @Synchronized
        fun load(alias: String, commonName: String): DeviceKey {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!ks.containsAlias(alias)) generate(alias, commonName)
            return DeviceKey(alias)
        }

        private fun generate(alias: String, commonName: String) {
            val notBefore = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
            val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // TLS 1.3 signs with ECDSA/SHA-256; NONE lets the TLS stack pass pre-hashed data.
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=$commonName"))
                .setCertificateSerialNumber(BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE))
                .setCertificateNotBefore(notBefore)
                .setCertificateNotAfter(notAfter)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
                initialize(spec)
                generateKeyPair()
            }
        }
    }
}
