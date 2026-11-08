package com.nshare.protocol

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The peer refused the session (ERROR frame) or the handshake broke its rules. */
class NspRefusedException(val code: String, message: String) : IOException(message)

/** HMAC proofs for PIN pairing; both directions bind both certificates and both nonces. */
object PinProof {
    private const val CLIENT = "NSP2-PAIR-CLIENT"
    private const val SERVER = "NSP2-PAIR-SERVER"

    fun newPin(): String = "%06d".format(SecureRandom().nextInt(1_000_000))

    fun newNonce(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun client(pin: String, tvFp: ByteArray, phoneFp: ByteArray, tvNonce: ByteArray, phoneNonce: ByteArray) =
        mac(CLIENT, pin, tvFp, phoneFp, tvNonce, phoneNonce)

    fun server(pin: String, tvFp: ByteArray, phoneFp: ByteArray, tvNonce: ByteArray, phoneNonce: ByteArray) =
        mac(SERVER, pin, tvFp, phoneFp, tvNonce, phoneNonce)

    fun matches(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    private fun mac(label: String, pin: String, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        m.update(label.toByteArray(Charsets.UTF_8))
        for (p in parts) m.update(p)
        return m.doFinal()
    }
}

private fun FrameWriter.error(code: String, message: String) =
    write(Nsp.Type.ERROR, payload = KeyValues.encode(mapOf("code" to code, "message" to message)))

private fun Frame.errorOrNull(): NspRefusedException? {
    if (type != Nsp.Type.ERROR) return null
    val kv = KeyValues.decode(payload)
    return NspRefusedException(kv["code"] ?: Nsp.Error.PROTOCOL, kv["message"] ?: "refused")
}

/** Receiver (TV) side decisions. Methods may block (e.g. waiting for the user). */
interface ServerPolicy {
    /** Whether PIN pairing is required for devices that are not trusted yet. */
    val pinPairing: Boolean
    fun isTrusted(fingerprintHex: String): Boolean
    fun trust(fingerprintHex: String, deviceName: String)
    fun showPin(pin: String, deviceName: String)
    fun hidePin()
    /** Returns false to refuse (e.g. the TV user declined a second sender). */
    fun approve(deviceName: String, fingerprintHex: String): Boolean
    /** Called before blocking in [approve] so the sender can show "waiting for approval". */
    fun needsApproval(): Boolean
}

/**
 * Receiver side of the v2 handshake, run on an already established TLS socket.
 * Returns the sender's HELLO once the sender is trusted and approved; CAPS has been sent.
 */
class ServerHandshake(
    private val reader: FrameReader,
    private val writer: FrameWriter,
    private val caps: Caps,
    private val policy: ServerPolicy,
    private val serverFp: ByteArray,
    private val clientFp: ByteArray,
) {
    /** Called once the peer has sent a valid HELLO (used to relax handshake timeouts during pairing). */
    var onHello: (Hello) -> Unit = {}

    fun run(): Hello {
        val first = reader.read(Nsp.MAX_HANDSHAKE_PAYLOAD)
        first.errorOrNull()?.let { throw it }
        if (first.type != Nsp.Type.HELLO) {
            writer.error(Nsp.Error.PROTOCOL, "expected HELLO")
            throw NspRefusedException(Nsp.Error.PROTOCOL, "expected HELLO, got ${first.type}")
        }
        val hello = Hello.decode(first.payload)
        if (hello.protocolVersion != Nsp.VERSION) {
            writer.error(Nsp.Error.VERSION, "receiver speaks v${Nsp.VERSION}")
            throw NspRefusedException(Nsp.Error.VERSION, "sender speaks v${hello.protocolVersion}")
        }
        onHello(hello)
        val fp = NspTls.hex(clientFp)
        if (!policy.isTrusted(fp)) {
            if (policy.pinPairing) pair(hello) else policy.trust(fp, hello.deviceName) // trust on first use
        }
        if (policy.needsApproval()) writer.write(Nsp.Type.WAIT_APPROVAL)
        if (!policy.approve(hello.deviceName, fp)) {
            writer.error(Nsp.Error.REJECTED, "declined on the TV")
            throw NspRefusedException(Nsp.Error.REJECTED, "declined")
        }
        writer.write(Nsp.Type.CAPS, payload = caps.encode())
        return hello
    }

    private fun pair(hello: Hello) {
        val pin = PinProof.newPin()
        val tvNonce = PinProof.newNonce()
        writer.write(Nsp.Type.PAIR_REQUIRED, payload = KeyValues.encode(mapOf("nonce" to NspTls.hex(tvNonce))))
        policy.showPin(pin, hello.deviceName)
        try {
            while (true) {
                val f = reader.read(Nsp.MAX_HANDSHAKE_PAYLOAD)
                f.errorOrNull()?.let { throw it }
                if (f.type != Nsp.Type.PAIR_PROOF) throw NspRefusedException(Nsp.Error.PROTOCOL, "expected PAIR_PROOF")
                val kv = KeyValues.decode(f.payload)
                val phoneNonce = NspTls.unhex(kv["nonce"] ?: "")
                val proof = NspTls.unhex(kv["mac"] ?: "")
                val expected = PinProof.client(pin, serverFp, clientFp, tvNonce, phoneNonce)
                if (PinProof.matches(proof, expected)) {
                    val mine = PinProof.server(pin, serverFp, clientFp, tvNonce, phoneNonce)
                    writer.write(Nsp.Type.PAIR_RESULT, payload = KeyValues.encode(mapOf("ok" to "1", "mac" to NspTls.hex(mine))))
                    policy.trust(NspTls.hex(clientFp), hello.deviceName)
                    return
                }
                // Wrong PIN: the sender may try again with the same PIN.
                writer.write(Nsp.Type.PAIR_RESULT, payload = KeyValues.encode(mapOf("ok" to "0")))
            }
        } finally {
            policy.hidePin()
        }
    }
}

/** Sender (phone) side decisions. */
interface ClientPolicy {
    /** Blocks until the user typed the PIN shown on the TV; null cancels. [retry] is true after a wrong PIN. */
    fun requestPin(retry: Boolean): String?
    fun onWaitingForApproval()
    /** The TV proved it knows the PIN: remember its fingerprint. */
    fun trustReceiver(fingerprintHex: String)
}

/** Sender side of the v2 handshake on an established TLS socket. Returns the receiver's CAPS. */
class ClientHandshake(
    private val reader: FrameReader,
    private val writer: FrameWriter,
    private val hello: Hello,
    private val policy: ClientPolicy,
    private val clientFp: ByteArray,
    private val serverFp: ByteArray,
) {
    /** True once PIN pairing happened in this handshake (the receiver required it). */
    var paired = false
        private set

    fun run(): Caps {
        writer.write(Nsp.Type.HELLO, payload = hello.encode())
        while (true) {
            val f = reader.read(Nsp.MAX_HANDSHAKE_PAYLOAD)
            f.errorOrNull()?.let { throw it }
            when (f.type) {
                Nsp.Type.CAPS -> return Caps.decode(f.payload)
                Nsp.Type.PAIR_REQUIRED -> pair(NspTls.unhex(KeyValues.decode(f.payload)["nonce"] ?: ""))
                Nsp.Type.WAIT_APPROVAL -> policy.onWaitingForApproval()
                Nsp.Type.PING -> Unit
                else -> throw NspRefusedException(Nsp.Error.PROTOCOL, "unexpected frame ${f.type} in handshake")
            }
        }
    }

    private fun pair(tvNonce: ByteArray) {
        var retry = false
        while (true) {
            val pin = policy.requestPin(retry)
            if (pin == null) {
                writer.error(Nsp.Error.PAIRING, "cancelled")
                throw NspRefusedException(Nsp.Error.PAIRING, "pairing cancelled")
            }
            val phoneNonce = PinProof.newNonce()
            val mac = PinProof.client(pin, serverFp, clientFp, tvNonce, phoneNonce)
            writer.write(
                Nsp.Type.PAIR_PROOF,
                payload = KeyValues.encode(mapOf("nonce" to NspTls.hex(phoneNonce), "mac" to NspTls.hex(mac))),
            )
            val r = reader.read(Nsp.MAX_HANDSHAKE_PAYLOAD)
            r.errorOrNull()?.let { throw it }
            if (r.type != Nsp.Type.PAIR_RESULT) throw NspRefusedException(Nsp.Error.PROTOCOL, "expected PAIR_RESULT")
            val kv = KeyValues.decode(r.payload)
            if (kv["ok"] != "1") {
                retry = true
                continue
            }
            // The TV must prove the same PIN, otherwise we are talking to an impostor.
            val expected = PinProof.server(pin, serverFp, clientFp, tvNonce, phoneNonce)
            if (!PinProof.matches(NspTls.unhex(kv["mac"] ?: ""), expected)) {
                throw NspRefusedException(Nsp.Error.PAIRING, "receiver failed to prove the PIN")
            }
            policy.trustReceiver(NspTls.hex(serverFp))
            paired = true
            return
        }
    }
}
