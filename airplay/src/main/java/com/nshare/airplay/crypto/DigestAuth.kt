package com.nshare.airplay.crypto

import java.security.MessageDigest
import java.security.SecureRandom

/** RTSP Digest access authentication (RFC 2617, MD5) used by AirPlay password protection. */
object DigestAuth {
    const val REALM = "raop"

    fun newNonce(): String = hex(ByteArray(16).also { SecureRandom().nextBytes(it) })

    fun challenge(nonce: String) = "Digest realm=\"$REALM\", nonce=\"$nonce\""

    /** Verifies an Authorization header against [password] and the nonce we issued. */
    fun verify(method: String, authorization: String, password: String, expectedNonce: String?): Boolean {
        if (!authorization.startsWith("Digest ", ignoreCase = true) || expectedNonce == null) return false
        val p = parse(authorization.substring(7))
        val username = p["username"] ?: return false
        val realm = p["realm"] ?: return false
        val nonce = p["nonce"] ?: return false
        val uri = p["uri"] ?: return false
        val response = p["response"] ?: return false
        if (nonce != expectedNonce) return false
        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("$method:$uri")
        val qop = p["qop"]
        val expected = if (qop != null) {
            md5("$ha1:$nonce:${p["nc"]}:${p["cnonce"]}:$qop:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }
        return MessageDigest.isEqual(expected.toByteArray(), response.lowercase().toByteArray())
    }

    private fun parse(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,\s]*))""").findAll(s).forEach { m ->
            out[m.groupValues[1].lowercase()] = m.groupValues[2].ifEmpty { m.groupValues[3] }
        }
        return out
    }

    fun md5(s: String): String = hex(MessageDigest.getInstance("MD5").digest(s.toByteArray()))

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
