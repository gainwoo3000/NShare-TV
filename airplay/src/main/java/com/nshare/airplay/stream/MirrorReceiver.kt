package com.nshare.airplay.stream

import android.util.Log
import com.nshare.airplay.VideoCodec
import com.nshare.airplay.VideoConfig
import com.nshare.airplay.VideoFrame
import com.nshare.airplay.VideoSink
import com.nshare.airplay.time.ClockSync
import com.nshare.airplay.util.Bytes
import com.nshare.airplay.util.H264Sps
import com.nshare.airplay.util.NtpTime
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Receives the screen mirroring stream (SETUP stream type 110).
 *
 * The sender connects to [dataPort] over TCP and sends packets made of a 128-byte header
 * followed by a payload:
 *  - header[0..3]  payload size (LE)
 *  - header[4]     type: 0 = encrypted video (AVCC), 1 = codec config (avcC / hvcC), 5 = stats plist
 *  - header[6]     option: 0x56/0x5e on a config packet means the stream is suspended
 *  - header[8..15] capture time, Q32.32 (LE)
 * Video payloads are AES-128-CTR encrypted with one continuous keystream per stream.
 */
class MirrorReceiver(
    aesKey: ByteArray,
    streamConnectionId: Long,
    private val clock: ClockSync,
    private val sink: VideoSink,
) {
    private val server = ServerSocket().apply {
        reuseAddress = true
        // Must be set before bind so the accepted socket advertises a large TCP window from the SYN:
        // the sender sizes its bitrate from the throughput it measures (txCapacity in its reports).
        receiveBufferSize = 8 * 1024 * 1024
        bind(InetSocketAddress(0))
    }
    val dataPort: Int get() = server.localPort

    private val cipher: Cipher
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var socket: Socket? = null

    private var codec: VideoCodec? = null
    private var pendingParameterSets: ByteArray? = null
    private var pendingParameterTs = 0L
    private var suspended = false

    @Volatile var bytesReceived = 0L
        private set
    @Volatile var framesReceived = 0L
        private set

    init {
        val id = java.lang.Long.toUnsignedString(streamConnectionId)
        val key = Bytes.sha512("AirPlayStreamKey$id".toByteArray(), aesKey).copyOf(16)
        val iv = Bytes.sha512("AirPlayStreamIV$id".toByteArray(), aesKey).copyOf(16)
        cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }

    fun start() {
        running = true
        thread = Thread(::run, "nshare-mirror").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try { server.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        try {
            val s = server.accept()
            socket = s
            s.keepAlive = true
            s.tcpNoDelay = true
            Log.i(TAG, "mirror stream connected from ${s.inetAddress.hostAddress} (rcvbuf=${s.receiveBufferSize})")
            val input = BufferedInputStream(s.getInputStream(), 256 * 1024)
            val header = ByteArray(128)
            while (running) {
                readFully(input, header, 128)
                val size = Bytes.i32le(header, 0)
                if (size < 0 || size > MAX_PAYLOAD) throw IllegalStateException("bad payload size $size")
                val payload = ByteArray(size)
                readFully(input, payload, size)
                bytesReceived += 128 + size
                handle(header, payload)
            }
        } catch (e: Exception) {
            if (running) Log.i(TAG, "mirror stream ended: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
            if (running) sink.onVideoStopped()
            running = false
        }
    }

    private fun handle(header: ByteArray, payload: ByteArray) {
        val type = header[4].toInt() and 0xff
        val option = header[6].toInt() and 0xff
        val rawTs = Bytes.i64le(header, 8)
        when (type) {
            0x00 -> handleVideo(payload, rawTs)
            0x01 -> handleConfig(header, payload, option, rawTs)
            0x05 -> logStreamReport(payload)
            else -> Unit // 0x02 keep-alive
        }
    }

    private fun handleVideo(payload: ByteArray, rawTs: Long) {
        val arrival = System.nanoTime()
        val plain = cipher.update(payload) ?: return
        if (plain.size != payload.size) {
            Log.w(TAG, "CTR returned ${plain.size} for ${payload.size} bytes")
            return
        }
        val codec = codec ?: return
        var keyFrame = false
        // AVCC (4-byte big-endian lengths) → Annex-B, in place.
        var pos = 0
        while (pos + 4 <= plain.size) {
            val len = Bytes.i32be(plain, pos)
            if (len <= 0 || len > plain.size - pos - 4) {
                Log.w(TAG, "invalid NAL length $len at $pos/${plain.size}: dropping frame")
                return
            }
            plain[pos] = 0; plain[pos + 1] = 0; plain[pos + 2] = 0; plain[pos + 3] = 1
            val nalHeader = plain[pos + 4].toInt() and 0xff
            if (nalHeader and 0x80 != 0) {
                Log.w(TAG, "forbidden bit set: decryption out of sync, dropping frame")
                return
            }
            keyFrame = keyFrame or when (codec) {
                VideoCodec.H264 -> (nalHeader and 0x1f) == 5
                VideoCodec.H265 -> ((nalHeader ushr 1) and 0x3f) in 16..21
            }
            pos += 4 + len
        }
        if (pos != plain.size) return

        val ps = pendingParameterSets
        val data = if (ps != null && pendingParameterTs == rawTs) {
            pendingParameterSets = null
            ps + plain
        } else {
            plain
        }
        val remoteNs = NtpTime.q32ToNanos(rawTs)
        clock.observeArrival(remoteNs, arrival)
        framesReceived++
        sink.onVideoFrame(VideoFrame(data, remoteNs, arrival, keyFrame))
    }

    private fun handleConfig(header: ByteArray, payload: ByteArray, option: Int, rawTs: Long) {
        val nowSuspended = option == 0x56 || option == 0x5e
        if (nowSuspended != suspended) {
            suspended = nowSuspended
            sink.onVideoPaused(nowSuspended)
        }
        if (payload.isEmpty()) {
            Log.w(TAG, "empty codec config (unsupported codec requested)")
            return
        }
        val width = Bytes.f32le(header, 56).toInt()
        val height = Bytes.f32le(header, 60).toInt()
        val isHevc = payload.size > 8 &&
            payload[4] == 'h'.code.toByte() && payload[5] == 'v'.code.toByte() &&
            payload[6] == 'c'.code.toByte() && payload[7] == '1'.code.toByte()
        val parameterSets = try {
            if (isHevc) parseHevcConfig(payload) else parseAvcConfig(payload)
        } catch (e: Exception) {
            Log.w(TAG, "bad codec config: ${e.message}")
            return
        }
        val newCodec = if (isHevc) VideoCodec.H265 else VideoCodec.H264
        codec = newCodec
        pendingParameterSets = parameterSets
        pendingParameterTs = rawTs
        Log.i(TAG, "codec config $newCodec ${width}x$height (${parameterSets.size} bytes)")
        sink.onVideoConfig(VideoConfig(newCodec, parameterSets, width, height))
    }

    private var reportsSeen = 0

    /** Sender-side encoder statistics (binary plist), logged periodically for diagnostics. */
    private fun logStreamReport(payload: ByteArray) {
        if (reportsSeen++ % 5 != 0) return
        val start = indexOf(payload, "bplist00".toByteArray())
        if (start < 0) return
        try {
            val plist = com.nshare.airplay.plist.BinaryPlist.decode(payload.copyOfRange(start, payload.size))
            Log.i(TAG, "sender report: ${plist.toString().take(1500)}")
        } catch (e: Exception) {
            Log.d(TAG, "unreadable sender report (${payload.size} bytes): ${e.message}")
        }
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    /** avcC: version, profile, compat, level, 0xFF, 0xE1, spsLen(2) sps, numPps(1), ppsLen(2) pps. */
    private fun parseAvcConfig(p: ByteArray): ByteArray {
        val spsLen = Bytes.u16be(p, 6)
        val originalSps = p.copyOfRange(8, 8 + spsLen)
        val ppsLen = Bytes.u16be(p, 8 + spsLen + 1)
        val ppsStart = 8 + spsLen + 3
        val pps = p.copyOfRange(ppsStart, ppsStart + ppsLen)
        val sps = try {
            H264Sps.patchLowLatency(originalSps).also {
                val info = H264Sps.parse(originalSps)
                Log.i(
                    TAG,
                    "SPS profile=${info.profileIdc} level=${info.levelIdc} refs=${info.maxNumRefFrames} " +
                        "reorder=${info.maxNumReorderFrames} -> patched for zero reorder\n" +
                        "  orig ${Bytes.hex(originalSps)}\n  new  ${Bytes.hex(it)}",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "SPS patch failed (${e.message}), using original ${Bytes.hex(originalSps)}")
            originalSps
        }
        return START_CODE + sps + START_CODE + pps
    }

    /** hvcC as sent by AirPlay: VPS/SPS/PPS arrays start at offset 0x75. */
    private fun parseHevcConfig(p: ByteArray): ByteArray {
        var pos = 0x75
        val out = ArrayList<ByteArray>(3)
        for (expected in intArrayOf(0xa0, 0xa1, 0xa2)) {
            if ((p[pos].toInt() and 0xff) != expected) throw IllegalStateException("unexpected hvcC array")
            val len = Bytes.u16be(p, pos + 3)
            pos += 5
            out += START_CODE + p.copyOfRange(pos, pos + len)
            pos += len
        }
        return out[0] + out[1] + out[2]
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    companion object {
        private const val TAG = "NShare.Mirror"
        private const val MAX_PAYLOAD = 16 * 1024 * 1024
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
