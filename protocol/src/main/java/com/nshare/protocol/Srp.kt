package com.nshare.protocol

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SRP-6a (RFC 5054 2048-bit group, SHA-256) for NShare PIN pairing.
 *
 * Unlike a MAC keyed with the PIN, an attacker who talks to one side learns nothing it can test
 * PINs against offline: every protocol run allows exactly one online guess.
 *
 * The identity is bound to both TLS certificates ([identity]). A man in the middle terminates TLS
 * with its own certificate on each side, so the phone and the TV compute different identities and
 * the proofs never match, even if it relays every pairing message.
 */
object Srp {
    val N = BigInteger(
        "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4" +
            "A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF60" +
            "95179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF" +
            "747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B907" +
            "8717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB37861" +
            "60279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DB" +
            "FBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73",
        16,
    )
    val G: BigInteger = BigInteger.valueOf(2)
    private val N_LEN = unsigned(N).size
    private val K = BigInteger(1, sha256(pad(N), pad(G)))
    private val random = SecureRandom()

    /** "NSP2-PAIR" + receiver certificate fingerprint + sender certificate fingerprint. */
    fun identity(serverFp: ByteArray, clientFp: ByteArray): ByteArray =
        "NSP2-PAIR".toByteArray() + serverFp + clientFp

    /** Receiver side: knows the PIN it displays. One instance per attempt. */
    class Server(private val identity: ByteArray, pin: String) {
        val salt: ByteArray = ByteArray(16).also { random.nextBytes(it) }
        private val v = G.modPow(x(salt, identity, pin), N)
        private val b = BigInteger(1, ByteArray(32).also { random.nextBytes(it) })
        val publicKey: ByteArray = unsigned(K.multiply(v).add(G.modPow(b, N)).mod(N))

        /** Returns the server proof M2 if the client's proof M1 is right, else null. */
        fun verify(clientPublic: ByteArray, clientProof: ByteArray): ByteArray? {
            val a = BigInteger(1, clientPublic)
            if (a.mod(N).signum() == 0) return null
            val bPub = BigInteger(1, publicKey)
            val u = BigInteger(1, sha256(pad(a), pad(bPub)))
            if (u.signum() == 0) return null
            val s = a.multiply(v.modPow(u, N)).mod(N).modPow(b, N)
            val key = sha256(pad(s))
            val m1 = m1(identity, salt, a, bPub, key)
            if (!MessageDigest.isEqual(m1, clientProof)) return null
            return sha256(pad(a), m1, key)
        }
    }

    /** Sender side: the user typed [pin]. */
    class Client(private val identity: ByteArray, private val pin: String) {
        private val a = BigInteger(1, ByteArray(32).also { random.nextBytes(it) })
        val publicKey: ByteArray = unsigned(G.modPow(a, N))
        private var expectedM2: ByteArray? = null

        /** Computes M1 for the server's salt and B, or null if B is invalid. */
        fun proof(salt: ByteArray, serverPublic: ByteArray): ByteArray? {
            val bPub = BigInteger(1, serverPublic)
            if (bPub.mod(N).signum() == 0) return null
            val aPub = BigInteger(1, publicKey)
            val u = BigInteger(1, sha256(pad(aPub), pad(bPub)))
            if (u.signum() == 0) return null
            val x = x(salt, identity, pin)
            val base = bPub.subtract(K.multiply(G.modPow(x, N))).mod(N)
            val s = base.modPow(a.add(u.multiply(x)), N)
            val key = sha256(pad(s))
            val m1 = m1(identity, salt, aPub, bPub, key)
            expectedM2 = sha256(pad(aPub), m1, key)
            return m1
        }

        /** True if the server proved it knows the same PIN for the same identity. */
        fun verifyServer(serverProof: ByteArray): Boolean =
            expectedM2?.let { MessageDigest.isEqual(it, serverProof) } ?: false
    }

    private fun x(salt: ByteArray, identity: ByteArray, pin: String): BigInteger =
        BigInteger(1, sha256(salt, sha256(identity, ":".toByteArray(), pin.toByteArray(Charsets.UTF_8))))

    private fun m1(identity: ByteArray, salt: ByteArray, a: BigInteger, b: BigInteger, key: ByteArray): ByteArray {
        val hN = sha256(pad(N))
        val hG = sha256(pad(G))
        val xor = ByteArray(hN.size) { (hN[it].toInt() xor hG[it].toInt()).toByte() }
        return sha256(xor, sha256(identity), salt, pad(a), pad(b), key)
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun unsigned(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        return if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
    }

    private fun pad(n: BigInteger): ByteArray {
        val raw = unsigned(n)
        return ByteArray(N_LEN - raw.size) + raw
    }
}
