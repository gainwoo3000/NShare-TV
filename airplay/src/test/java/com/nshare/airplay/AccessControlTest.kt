package com.nshare.airplay

import com.nshare.airplay.crypto.DigestAuth
import com.nshare.airplay.crypto.SrpServer
import com.nshare.airplay.crypto.SrpServer.Companion.G
import com.nshare.airplay.crypto.SrpServer.Companion.N
import com.nshare.airplay.crypto.SrpServer.Companion.pad
import com.nshare.airplay.crypto.SrpServer.Companion.sha1
import com.nshare.airplay.crypto.SrpServer.Companion.unsigned
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

class AccessControlTest {

    /** Client side of Apple's SRP-6a variant, written from the protocol description. */
    private class SrpClient(val user: String, val pin: String) {
        private val a = BigInteger(1, ByteArray(32).also { SecureRandom().nextBytes(it) })
        val publicKey: ByteArray = unsigned(G.modPow(a, N))
        lateinit var key: ByteArray
        lateinit var m1: ByteArray

        fun proof(salt: ByteArray, serverPublic: ByteArray): ByteArray {
            val bPub = BigInteger(1, serverPublic)
            val aPub = BigInteger(1, publicKey)
            val x = BigInteger(1, sha1(salt, sha1("$user:$pin".toByteArray())))
            val k = BigInteger(1, sha1(pad(N), pad(G)))
            val u = BigInteger(1, sha1(pad(aPub), pad(bPub)))
            // S = (B - k * g^x) ^ (a + u * x) mod N
            val base = bPub.subtract(k.multiply(G.modPow(x, N))).mod(N)
            val s = base.modPow(a.add(u.multiply(x)), N)
            val sBytes = unsigned(s)
            key = sha1(sBytes, byteArrayOf(0, 0, 0, 0)) + sha1(sBytes, byteArrayOf(0, 0, 0, 1))
            val hN = sha1(unsigned(N))
            val hG = sha1(unsigned(G))
            val xor = ByteArray(20) { (hN[it].toInt() xor hG[it].toInt()).toByte() }
            m1 = sha1(xor, sha1(user.toByteArray()), unsigned(BigInteger(1, salt)), publicKey, unsigned(bPub), key)
            return m1
        }

        fun expectedServerProof(): ByteArray = sha1(publicKey, m1, key)
    }

    @Test
    fun srpPairingWithCorrectPin() {
        val server = SrpServer("AA:BB:CC:DD:EE:FF", "4821")
        assertEquals(16, server.salt.size)
        val client = SrpClient("AA:BB:CC:DD:EE:FF", "4821")
        val m1 = client.proof(server.salt, server.publicKey)
        val m2 = server.verify(client.publicKey, m1)
        assertNotNull(m2)
        assertArrayEquals(client.expectedServerProof(), m2)
        assertEquals(40, server.sessionKey!!.size)
        assertArrayEquals(client.key, server.sessionKey)
    }

    @Test
    fun srpRejectsWrongPin() {
        val server = SrpServer("device", "4821")
        val client = SrpClient("device", "4822")
        assertNull(server.verify(client.publicKey, client.proof(server.salt, server.publicKey)))
        assertNull(server.sessionKey)
    }

    @Test
    fun srpRejectsZeroPublicValue() {
        val server = SrpServer("device", "0000")
        assertNull(server.verify(unsigned(N), ByteArray(20)))
    }

    @Test
    fun pairSetupPinKeyExchangeRoundTrip() {
        // Step 3 of pair-setup-pin: AES-128-GCM with a 16-byte IV derived from the SRP session key.
        val key = ByteArray(40) { it.toByte() }
        val aesKey = com.nshare.airplay.util.Bytes.sha512("Pair-Setup-AES-Key".toByteArray(), key).copyOf(16)
        val iv = com.nshare.airplay.util.Bytes.sha512("Pair-Setup-AES-IV".toByteArray(), key).copyOf(16).also { it[15]++ }
        val clientPk = ByteArray(32) { (it * 7).toByte() }
        fun gcm(mode: Int, ivx: ByteArray, input: ByteArray) = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, javax.crypto.spec.SecretKeySpec(aesKey, "AES"), javax.crypto.spec.GCMParameterSpec(128, ivx))
            doFinal(input)
        }
        val sealed = gcm(javax.crypto.Cipher.ENCRYPT_MODE, iv, clientPk)
        assertEquals(48, sealed.size) // 32-byte epk + 16-byte authTag
        assertArrayEquals(clientPk, gcm(javax.crypto.Cipher.DECRYPT_MODE, iv, sealed))
        val tampered = sealed.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        try {
            gcm(javax.crypto.Cipher.DECRYPT_MODE, iv, tampered)
            throw AssertionError("tampered tag accepted")
        } catch (_: javax.crypto.AEADBadTagException) {
        }
    }

    @Test
    fun digestMatchesRfc2617Example() {
        // RFC 2617 section 3.5 (qop=auth).
        val header = "Digest username=\"Mufasa\", realm=\"testrealm@host.com\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", " +
            "uri=\"/dir/index.html\", qop=auth, nc=00000001, cnonce=\"0a4f113b\", " +
            "response=\"6629fae49393a05397450978507c4ef1\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""
        assertTrue(DigestAuth.verify("GET", header, "Circle Of Life", "dcd98b7102dd2f0e8b11d0f600bfb0c093"))
        assertFalse(DigestAuth.verify("GET", header, "wrong", "dcd98b7102dd2f0e8b11d0f600bfb0c093"))
        assertFalse("stale nonce", DigestAuth.verify("GET", header, "Circle Of Life", "another"))
    }

    @Test
    fun digestWithoutQopAsSentByAirPlay() {
        val nonce = DigestAuth.newNonce()
        val ha1 = DigestAuth.md5("iTunes:raop:secret")
        val ha2 = DigestAuth.md5("SETUP:rtsp://192.168.0.2/123")
        val response = DigestAuth.md5("$ha1:$nonce:$ha2")
        val header = "Digest username=\"iTunes\", realm=\"raop\", nonce=\"$nonce\", uri=\"rtsp://192.168.0.2/123\", response=\"$response\""
        assertTrue(DigestAuth.verify("SETUP", header, "secret", nonce))
        assertFalse(DigestAuth.verify("SETUP", header, "Secret", nonce))
    }
}
