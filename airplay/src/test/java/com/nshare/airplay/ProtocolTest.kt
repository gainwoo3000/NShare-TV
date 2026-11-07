package com.nshare.airplay

import com.nshare.airplay.crypto.Identity
import com.nshare.airplay.crypto.PairVerify
import com.nshare.airplay.plist.BinaryPlist
import com.nshare.airplay.util.Bytes
import com.nshare.airplay.util.NtpTime
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProtocolTest {

    @Test
    fun plistRoundTrip() {
        val src = linkedMapOf<String, Any?>(
            "name" to "NShare-테스트",
            "features" to 0x5A7FFEE6L,
            "big" to 0x1_0000_0000L,
            "neg" to -5L,
            "pk" to ByteArray(32) { it.toByte() },
            "long" to "x".repeat(40),
            "real" to 1.0 / 60.0,
            "flag" to true,
            "streams" to listOf(mapOf("type" to 110L, "dataPort" to 7100L)),
        )
        @Suppress("UNCHECKED_CAST")
        val back = BinaryPlist.decode(BinaryPlist.encode(src)) as Map<String, Any?>
        assertEquals("NShare-테스트", back["name"])
        assertEquals(0x5A7FFEE6L, back["features"])
        assertEquals(0x1_0000_0000L, back["big"])
        assertEquals(-5L, back["neg"])
        assertArrayEquals(src["pk"] as ByteArray, back["pk"] as ByteArray)
        assertEquals("x".repeat(40), back["long"])
        assertEquals(1.0 / 60.0, back["real"] as Double, 1e-12)
        assertEquals(true, back["flag"])
        assertEquals(listOf(mapOf("type" to 110L, "dataPort" to 7100L)), back["streams"])
    }

    @Test
    fun plistDecodes128BitUnsignedInt() {
        // { "id": <16-byte int 0xF000000000000001> } as libplist/Apple would encode it.
        val obj = byteArrayOf(
            0xD1.toByte(), 1, 2, // dict, 1 entry, key ref 1, value ref 2
            0x52, 'i'.code.toByte(), 'd'.code.toByte(),
            0x14, 0, 0, 0, 0, 0, 0, 0, 0,
            0xF0.toByte(), 0, 0, 0, 0, 0, 0, 1,
        )
        val header = "bplist00".toByteArray()
        val offsets = byteArrayOf(8, 11, 14)
        val trailer = ByteArray(32).also {
            it[6] = 1; it[7] = 1; it[15] = 3
            it[31] = (header.size + obj.size).toByte()
        }
        val plist = header + obj + offsets + trailer
        @Suppress("UNCHECKED_CAST")
        val map = BinaryPlist.decode(plist) as Map<String, Any?>
        assertEquals("17293822569102704641", java.lang.Long.toUnsignedString(map["id"] as Long))
    }

    @Test
    fun pairVerifyAgainstSimulatedClient() {
        val server = Identity(Identity.generateSecret())

        // Client long-term and ephemeral keys.
        val clientEdSecret = ByteArray(32).also { Ed25519.generatePrivateKey(SecureRandom(), it) }
        val clientEdPublic = ByteArray(32).also { Ed25519.generatePublicKey(clientEdSecret, 0, it, 0) }
        val clientEcdhSecret = ByteArray(32).also { X25519.generatePrivateKey(SecureRandom(), it) }
        val clientEcdhPublic = ByteArray(32).also { X25519.generatePublicKey(clientEcdhSecret, 0, it, 0) }

        val pv = PairVerify(server)
        val reply = pv.handshake(clientEcdhPublic, clientEdPublic)
        assertEquals(96, reply.size)
        val serverEcdh = reply.copyOfRange(0, 32)

        // Client derives the same shared secret and checks the server signature.
        val shared = ByteArray(32).also { X25519.calculateAgreement(clientEcdhSecret, 0, serverEcdh, 0, it, 0) }
        assertArrayEquals(pv.sharedSecret, shared)
        val key = Bytes.sha512("Pair-Verify-AES-Key".toByteArray(), shared).copyOf(16)
        val iv = Bytes.sha512("Pair-Verify-AES-IV".toByteArray(), shared).copyOf(16)
        val ctr = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        val serverSig = ctr.update(reply.copyOfRange(32, 96))
        val signed = serverEcdh + clientEcdhPublic
        assertTrue(Ed25519.verify(serverSig, 0, server.publicKey, 0, signed, 0, signed.size))

        // Client signs (clientEcdh || serverEcdh) and encrypts with the continued keystream.
        val clientMsg = clientEcdhPublic + serverEcdh
        val clientSig = ByteArray(64).also { Ed25519.sign(clientEdSecret, 0, clientMsg, 0, clientMsg.size, it, 0) }
        val encrypted = ctr.update(clientSig)
        assertTrue(pv.finish(encrypted))

        val tampered = encrypted.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(PairVerify(server).run {
            handshake(clientEcdhPublic, clientEdPublic)
            finish(tampered)
        })
    }

    @Test
    fun ctrKeystreamIsContinuousAcrossUpdates() {
        // The mirror stream relies on one CTR keystream spanning packets of arbitrary length.
        val key = SecretKeySpec(ByteArray(16) { 7 }, "AES")
        val iv = IvParameterSpec(ByteArray(16) { 3 })
        val data = ByteArray(1000) { (it * 31).toByte() }
        val whole = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, iv) }.doFinal(data)
        val c = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, iv) }
        val parts = listOf(5, 123, 16, 1, 400, 455)
        var off = 0
        val pieces = parts.map { n -> c.update(data, off, n).also { off += n } }
        assertArrayEquals(whole, pieces.reduce { a, b -> a + b })
    }

    @Test
    fun ntpConversionsAgree() {
        // Video headers carry Q32.32 without the 1900 offset; timing packets include it.
        val secs = 12345L
        val frac = 0x8000_0000L
        val video = (secs shl 32) or frac
        val ntp = ((secs + NtpTime.SECONDS_1900_TO_1970) shl 32) or frac
        assertEquals(NtpTime.q32ToNanos(video), NtpTime.ntpToNanos(ntp))
        assertEquals(12_345_500_000_000L, NtpTime.q32ToNanos(video))
    }
}
