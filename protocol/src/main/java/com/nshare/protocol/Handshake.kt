package com.nshare.protocol

import java.io.IOException
import java.security.SecureRandom

/** The peer refused the session (ERROR frame) or the handshake broke its rules. */
class NspRefusedException(val code: String, message: String) : IOException(message)

/** PIN shown on the TV for pairing; checked with SRP-6a ([Srp]), never sent over the network. */
object Pin {
    fun newPin(): String = "%06d".format(SecureRandom().nextInt(1_000_000))
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
    /** Claims the receiver's single pairing slot; false while another device is pairing. */
    fun beginPairing(): Boolean = true
    fun endPairing() {}
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
        val newDevice = !policy.isTrusted(fp)
        if (newDevice && policy.pinPairing) pair(hello)
        if (policy.needsApproval()) writer.write(Nsp.Type.WAIT_APPROVAL)
        if (!policy.approve(hello.deviceName, fp)) {
            writer.error(Nsp.Error.REJECTED, "declined on the TV")
            throw NspRefusedException(Nsp.Error.REJECTED, "declined")
        }
        // Remember a new device (PIN pairing or trust on first use) only once it is fully admitted:
        // a sender the TV user declined must not end up trusted.
        if (newDevice) policy.trust(fp, hello.deviceName)
        writer.write(Nsp.Type.CAPS, payload = caps.encode())
        return hello
    }

    private fun pair(hello: Hello) {
        if (!policy.beginPairing()) {
            writer.error(Nsp.Error.BUSY, "another device is pairing with this TV")
            throw NspRefusedException(Nsp.Error.BUSY, "pairing busy")
        }
        try {
            val pin = Pin.newPin()
            val identity = Srp.identity(serverFp, clientFp)
            policy.showPin(pin, hello.deviceName)
            while (true) {
                // Fresh SRP values for every attempt; the PIN on screen stays the same.
                val srp = Srp.Server(identity, pin)
                writer.write(
                    Nsp.Type.PAIR_REQUIRED,
                    payload = KeyValues.encode(mapOf("salt" to NspTls.hex(srp.salt), "pk" to NspTls.hex(srp.publicKey))),
                )
                val f = reader.read(Nsp.MAX_HANDSHAKE_PAYLOAD)
                f.errorOrNull()?.let { throw it }
                if (f.type != Nsp.Type.PAIR_PROOF) throw NspRefusedException(Nsp.Error.PROTOCOL, "expected PAIR_PROOF")
                val kv = KeyValues.decode(f.payload)
                val serverProof = srp.verify(NspTls.unhex(kv["pk"] ?: ""), NspTls.unhex(kv["proof"] ?: ""))
                if (serverProof != null) {
                    writer.write(Nsp.Type.PAIR_RESULT, payload = KeyValues.encode(mapOf("ok" to "1", "proof" to NspTls.hex(serverProof))))
                    return
                }
                // Wrong PIN (or a relayed exchange): the sender may try again.
                writer.write(Nsp.Type.PAIR_RESULT, payload = KeyValues.encode(mapOf("ok" to "0")))
            }
        } finally {
            policy.hidePin()
            policy.endPairing()
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
        var retry = false
        while (true) {
            val f = reader.read(Nsp.MAX_HANDSHAKE_PAYLOAD)
            f.errorOrNull()?.let { throw it }
            when (f.type) {
                Nsp.Type.CAPS -> {
                    // Like the TV, remember the receiver only once it actually admitted us.
                    if (paired) policy.trustReceiver(NspTls.hex(serverFp))
                    return Caps.decode(f.payload)
                }
                Nsp.Type.PAIR_REQUIRED -> if (!pair(KeyValues.decode(f.payload), retry)) retry = true
                Nsp.Type.WAIT_APPROVAL -> policy.onWaitingForApproval()
                Nsp.Type.PING -> Unit
                else -> throw NspRefusedException(Nsp.Error.PROTOCOL, "unexpected frame ${f.type} in handshake")
            }
        }
    }

    /** One SRP attempt; returns false after a wrong PIN (the TV then sends a new PAIR_REQUIRED). */
    private fun pair(challenge: Map<String, String>, retry: Boolean): Boolean {
        val pin = policy.requestPin(retry)
        if (pin == null) {
            writer.error(Nsp.Error.PAIRING, "cancelled")
            throw NspRefusedException(Nsp.Error.PAIRING, "pairing cancelled")
        }
        val srp = Srp.Client(Srp.identity(serverFp, clientFp), pin)
        val proof = srp.proof(NspTls.unhex(challenge["salt"] ?: ""), NspTls.unhex(challenge["pk"] ?: ""))
            ?: throw NspRefusedException(Nsp.Error.PROTOCOL, "invalid pairing challenge")
        writer.write(
            Nsp.Type.PAIR_PROOF,
            payload = KeyValues.encode(mapOf("pk" to NspTls.hex(srp.publicKey), "proof" to NspTls.hex(proof))),
        )
        val r = reader.read(Nsp.MAX_HANDSHAKE_PAYLOAD)
        r.errorOrNull()?.let { throw it }
        if (r.type != Nsp.Type.PAIR_RESULT) throw NspRefusedException(Nsp.Error.PROTOCOL, "expected PAIR_RESULT")
        val kv = KeyValues.decode(r.payload)
        if (kv["ok"] != "1") return false
        // The TV must prove the same PIN for the same pair of certificates, otherwise it is an impostor.
        if (!srp.verifyServer(NspTls.unhex(kv["proof"] ?: ""))) {
            throw NspRefusedException(Nsp.Error.PAIRING, "receiver failed to prove the PIN")
        }
        paired = true
        return true
    }
}
