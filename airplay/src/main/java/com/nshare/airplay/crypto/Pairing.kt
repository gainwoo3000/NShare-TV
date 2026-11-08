package com.nshare.airplay.crypto

import com.nshare.airplay.util.Bytes
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Long-term Ed25519 identity of this receiver. The public key is advertised as "pk". */
class Identity(val secretKey: ByteArray) {
    val publicKey: ByteArray = ByteArray(Ed25519.PUBLIC_KEY_SIZE).also {
        Ed25519.generatePublicKey(secretKey, 0, it, 0)
    }

    fun sign(message: ByteArray): ByteArray {
        val sig = ByteArray(Ed25519.SIGNATURE_SIZE)
        Ed25519.sign(secretKey, 0, message, 0, message.size, sig, 0)
        return sig
    }

    companion object {
        fun generateSecret(): ByteArray = ByteArray(Ed25519.SECRET_KEY_SIZE).also {
            Ed25519.generatePrivateKey(SecureRandom(), it)
        }
    }
}

/**
 * AirPlay legacy pair-verify (the non-HomeKit variant used when features bit 27 is set).
 *
 * Step 1: client sends its X25519 and Ed25519 public keys; we answer with our X25519 public key
 *         and our signature over (ourEcdh || theirEcdh), AES-128-CTR encrypted with keys derived
 *         from the shared secret.
 * Step 2: client sends its encrypted signature over (theirEcdh || ourEcdh), continuing the same
 *         CTR keystream after our 64 bytes.
 */
class PairVerify(private val identity: Identity) {
    private val ecdhSecret = ByteArray(X25519.SCALAR_SIZE)
    val ecdhPublic = ByteArray(X25519.POINT_SIZE)
    private var theirEcdh: ByteArray? = null
    private var theirEd: ByteArray? = null

    /** 32-byte X25519 shared secret, available after [handshake]. */
    var sharedSecret: ByteArray? = null
        private set

    var verified = false
        private set

    /** Ed25519 key the sender presented (proven once [verified]). */
    val clientPublicKey: ByteArray? get() = theirEd

    /** Returns ourEcdhPublic (32) || encryptedSignature (64). */
    fun handshake(clientEcdh: ByteArray, clientEd: ByteArray): ByteArray {
        X25519.generatePrivateKey(SecureRandom(), ecdhSecret)
        X25519.generatePublicKey(ecdhSecret, 0, ecdhPublic, 0)
        val shared = ByteArray(X25519.POINT_SIZE)
        X25519.calculateAgreement(ecdhSecret, 0, clientEcdh, 0, shared, 0)
        sharedSecret = shared
        theirEcdh = clientEcdh.copyOf()
        theirEd = clientEd.copyOf()

        val signature = identity.sign(ecdhPublic + clientEcdh)
        val encrypted = ctr(shared).update(signature)
        return ecdhPublic + encrypted
    }

    /** Verifies the client's signature. Returns true if valid. */
    fun finish(encryptedSignature: ByteArray): Boolean {
        val shared = sharedSecret ?: return false
        val cipher = ctr(shared)
        cipher.update(ByteArray(64)) // skip the keystream used for our own signature
        val signature = cipher.update(encryptedSignature)
        val msg = theirEcdh!! + ecdhPublic
        verified = Ed25519.verify(signature, 0, theirEd!!, 0, msg, 0, msg.size)
        return verified
    }

    private fun ctr(shared: ByteArray): Cipher {
        val key = Bytes.sha512("Pair-Verify-AES-Key".toByteArray(), shared).copyOf(16)
        val iv = Bytes.sha512("Pair-Verify-AES-IV".toByteArray(), shared).copyOf(16)
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }
}
