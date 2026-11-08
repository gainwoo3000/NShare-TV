package com.nshare.airplay.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Server side of SRP-6a as used by AirPlay "PIN on screen" pairing (pair-setup-pin):
 * SHA-1, the RFC 5054 2048-bit group, k = H(N | PAD(g)), u = H(PAD(A) | PAD(B)),
 * x = H(s | H(I ":" P)), and Apple's variant of the session key K = H(S | 0000) || H(S | 0001)
 * (40 bytes) which is also what goes into M and H(A, M, K).
 */
class SrpServer(private val username: String, pin: String, random: SecureRandom = SecureRandom()) {
    val salt: ByteArray = ByteArray(16).also {
        random.nextBytes(it)
        if (it[0].toInt() == 0) it[0] = 1 // keep the salt exactly 16 bytes in every encoding
    }
    private val b = BigInteger(1, ByteArray(32).also { random.nextBytes(it) })
    private val v: BigInteger
    /** Server public value B, sent to the client as "pk". */
    val publicKey: ByteArray

    /** 40-byte session key, available after [verify] succeeded. */
    var sessionKey: ByteArray? = null
        private set

    init {
        val x = BigInteger(1, sha1(salt, sha1((username + ":" + pin).toByteArray())))
        v = G.modPow(x, N)
        val k = BigInteger(1, sha1(pad(N), pad(G)))
        publicKey = unsigned(k.multiply(v).add(G.modPow(b, N)).mod(N))
    }

    /** Checks the client's proof M1 for its public value A; returns the server proof H(A, M, K) or null. */
    fun verify(clientPublic: ByteArray, clientProof: ByteArray): ByteArray? {
        val a = BigInteger(1, clientPublic)
        if (a.mod(N) == BigInteger.ZERO) return null // SRP-6a safety check
        val bPub = BigInteger(1, publicKey)
        val u = BigInteger(1, sha1(pad(a), pad(bPub)))
        val s = a.multiply(v.modPow(u, N)).modPow(b, N)
        val sBytes = unsigned(s)
        val key = sha1(sBytes, byteArrayOf(0, 0, 0, 0)) + sha1(sBytes, byteArrayOf(0, 0, 0, 1))
        val hN = sha1(unsigned(N))
        val hG = sha1(unsigned(G))
        val xor = ByteArray(hN.size) { (hN[it].toInt() xor hG[it].toInt()).toByte() }
        val m = sha1(xor, sha1(username.toByteArray()), unsigned(BigInteger(1, salt)), unsigned(a), unsigned(bPub), key)
        if (!MessageDigest.isEqual(m, clientProof)) return null
        sessionKey = key
        return sha1(unsigned(a), m, key)
    }

    companion object {
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

        fun sha1(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-1")
            parts.forEach { md.update(it) }
            return md.digest()
        }

        /** Minimal big-endian unsigned encoding (OpenSSL BN_bn2bin). */
        fun unsigned(n: BigInteger): ByteArray {
            val raw = n.toByteArray()
            return if (raw.size > 1 && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        }

        /** Left-padded to the length of N (RFC 5054 PAD). */
        fun pad(n: BigInteger): ByteArray {
            val raw = unsigned(n)
            return ByteArray(N_LEN - raw.size) + raw
        }
    }
}
