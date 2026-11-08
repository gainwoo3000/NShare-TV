package com.nshare.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * NShare streaming protocol, version 2.
 *
 * One TLS 1.3 connection per session (sender → receiver port [Nsp.DEFAULT_PORT]), mutually
 * authenticated with self-signed device certificates that are pinned by fingerprint (see [NspTls],
 * [ServerHandshake], [ClientHandshake]). Inside TLS the stream carries frames:
 *
 *   magic   u16  0x4E53 ("NS")
 *   type    u8   [Nsp.Type]
 *   flags   u8   bit0 = key frame
 *   length  u32  payload length
 *   timeNs  i64  sender System.nanoTime() of capture (0 if not applicable)
 *   payload
 *
 * Handshake: sender HELLO → [PAIR_REQUIRED / PAIR_PROOF / PAIR_RESULT] → [WAIT_APPROVAL] → receiver CAPS
 * (or ERROR) → sender VIDEO_CONFIG / AUDIO_CONFIG → media. After CAPS both sides send PING every
 * [PING_INTERVAL_MS] and drop the session after [IDLE_TIMEOUT_MS] without any frame.
 * Clock sync uses the AirPlay-style NTP exchange over UDP: the receiver sends timing requests
 * to the sender's timing port (announced in HELLO) and the sender answers with its clock.
 *
 * Version 1 (plain TCP, no PING) is not compatible; peers refuse it with an "update the app" error.
 */
object Nsp {
    const val VERSION = 2
    const val SERVICE_TYPE = "_nshare._tcp"
    const val DEFAULT_PORT = 7100
    const val MAGIC = 0x4E53
    const val HEADER_SIZE = 16
    const val MAX_PAYLOAD = 32 * 1024 * 1024
    /** Payload limit before the handshake completes (HELLO/CAPS are a few hundred bytes). */
    const val MAX_HANDSHAKE_PAYLOAD = 16 * 1024

    const val PING_INTERVAL_MS = 1_000L
    const val IDLE_TIMEOUT_MS = 5_000
    /** TLS + HELLO must complete within this time. */
    const val HANDSHAKE_TIMEOUT_MS = 10_000L
    /** Absolute limit for the whole handshake once HELLO arrived (time to type the PIN shown on the TV). */
    const val PAIRING_TIMEOUT_MS = 120_000
    /** Time the TV user has to accept a second sender. */
    const val APPROVAL_TIMEOUT_MS = 10_000L

    object Type {
        const val HELLO = 1
        const val CAPS = 2
        const val VIDEO_CONFIG = 3
        const val VIDEO_FRAME = 4
        const val AUDIO_CONFIG = 5
        const val AUDIO_FRAME = 6
        const val KEYFRAME_REQUEST = 7
        const val BYE = 8
        const val PING = 9
        const val ERROR = 10
        const val PAIR_REQUIRED = 11
        const val PAIR_PROOF = 12
        const val PAIR_RESULT = 13
        const val WAIT_APPROVAL = 14
    }

    /** Codes carried by ERROR frames. */
    object Error {
        const val VERSION = "version"
        const val REJECTED = "rejected"
        const val PAIRING = "pairing"
        const val PROTOCOL = "protocol"
        /** Another device is pairing with the receiver right now. */
        const val BUSY = "busy"
    }

    const val FLAG_KEY_FRAME = 1

    object VideoCodecId {
        const val H264 = 1
        const val H265 = 2
    }

    object AudioCodecId {
        const val AAC = 1
    }
}

class Frame(val type: Int, val flags: Int, val timeNs: Long, val payload: ByteArray) {
    val isKeyFrame: Boolean get() = flags and Nsp.FLAG_KEY_FRAME != 0
}

class FrameReader(private val input: DataInputStream) {
    /** Reads one frame; payloads larger than [maxPayload] are rejected before anything is allocated. */
    fun read(maxPayload: Int = Nsp.MAX_PAYLOAD): Frame {
        val magic = try {
            input.readUnsignedShort()
        } catch (e: EOFException) {
            throw e
        }
        if (magic != Nsp.MAGIC) throw IOException("bad magic 0x${magic.toString(16)}")
        val type = input.readUnsignedByte()
        val flags = input.readUnsignedByte()
        val length = input.readInt()
        if (length < 0 || length > maxPayload) throw IOException("bad length $length (limit $maxPayload)")
        val time = input.readLong()
        val payload = ByteArray(length)
        input.readFully(payload)
        return Frame(type, flags, time, payload)
    }
}

/** Thread-safe frame writer. */
class FrameWriter(private val out: DataOutputStream) {
    @Synchronized
    fun write(type: Int, flags: Int = 0, timeNs: Long = 0, payload: ByteArray = EMPTY, extra: ByteArray? = null) {
        val len = payload.size + (extra?.size ?: 0)
        out.writeShort(Nsp.MAGIC)
        out.writeByte(type)
        out.writeByte(flags)
        out.writeInt(len)
        out.writeLong(timeNs)
        if (extra != null) out.write(extra)
        out.write(payload)
        out.flush()
    }

    companion object {
        private val EMPTY = ByteArray(0)
    }
}

/** Small "key=value" line format used for HELLO / CAPS (keeps the protocol free of JSON deps). */
object KeyValues {
    fun encode(map: Map<String, Any?>): ByteArray =
        map.entries.filter { it.value != null }
            .joinToString("\n") { "${it.key}=${it.value.toString().replace('\n', ' ')}" }
            .toByteArray(StandardCharsets.UTF_8)

    fun decode(data: ByteArray): Map<String, String> =
        String(data, StandardCharsets.UTF_8).lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }
            .toMap()
}

/** HELLO, sender → receiver. */
class Hello(
    val deviceName: String,
    val model: String,
    val timingPort: Int,
    val protocolVersion: Int = Nsp.VERSION,
) {
    fun encode(): ByteArray = KeyValues.encode(
        mapOf("version" to protocolVersion, "name" to deviceName, "model" to model, "timingPort" to timingPort),
    )

    companion object {
        fun decode(data: ByteArray): Hello {
            val m = KeyValues.decode(data)
            return Hello(
                deviceName = m["name"] ?: "Android",
                model = m["model"] ?: "",
                timingPort = m["timingPort"]?.toIntOrNull() ?: 0,
                protocolVersion = m["version"]?.toIntOrNull() ?: 0,
            )
        }
    }
}

/** CAPS, receiver → sender: what to encode. */
class Caps(
    val receiverName: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int,
    val hevc: Boolean,
) {
    fun encode(): ByteArray = KeyValues.encode(
        mapOf(
            "version" to Nsp.VERSION,
            "name" to receiverName,
            "maxWidth" to maxWidth,
            "maxHeight" to maxHeight,
            "maxFps" to maxFps,
            "codecs" to if (hevc) "h265,h264" else "h264",
        ),
    )

    companion object {
        fun decode(data: ByteArray): Caps {
            val m = KeyValues.decode(data)
            return Caps(
                receiverName = m["name"] ?: "NShare",
                maxWidth = m["maxWidth"]?.toIntOrNull() ?: 1920,
                maxHeight = m["maxHeight"]?.toIntOrNull() ?: 1080,
                maxFps = m["maxFps"]?.toIntOrNull() ?: 60,
                hevc = m["codecs"]?.split(',')?.contains("h265") == true,
            )
        }
    }
}

/** VIDEO_CONFIG payload: codec u8, width u16, height u16, then Annex-B parameter sets. */
object VideoConfigCodec {
    fun header(codec: Int, width: Int, height: Int): ByteArray =
        byteArrayOf(codec.toByte(), (width ushr 8).toByte(), width.toByte(), (height ushr 8).toByte(), height.toByte())

    class Parsed(val codec: Int, val width: Int, val height: Int, val parameterSets: ByteArray)

    fun parse(p: ByteArray): Parsed {
        if (p.size < 5) throw IOException("short video config")
        val w = ((p[1].toInt() and 0xff) shl 8) or (p[2].toInt() and 0xff)
        val h = ((p[3].toInt() and 0xff) shl 8) or (p[4].toInt() and 0xff)
        return Parsed(p[0].toInt() and 0xff, w, h, p.copyOfRange(5, p.size))
    }
}

/** AUDIO_CONFIG payload: codec u8, sampleRate u32, channels u8, then codec specific data (AAC ASC). */
object AudioConfigCodec {
    fun header(codec: Int, sampleRate: Int, channels: Int): ByteArray = byteArrayOf(
        codec.toByte(),
        (sampleRate ushr 24).toByte(), (sampleRate ushr 16).toByte(), (sampleRate ushr 8).toByte(), sampleRate.toByte(),
        channels.toByte(),
    )

    class Parsed(val codec: Int, val sampleRate: Int, val channels: Int, val csd: ByteArray)

    fun parse(p: ByteArray): Parsed {
        if (p.size < 6) throw IOException("short audio config")
        val sr = ((p[1].toInt() and 0xff) shl 24) or ((p[2].toInt() and 0xff) shl 16) or
            ((p[3].toInt() and 0xff) shl 8) or (p[4].toInt() and 0xff)
        return Parsed(p[0].toInt() and 0xff, sr, p[5].toInt() and 0xff, p.copyOfRange(6, p.size))
    }
}
