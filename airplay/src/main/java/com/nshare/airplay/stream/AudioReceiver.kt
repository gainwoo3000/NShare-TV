package com.nshare.airplay.stream

import android.util.Log
import com.nshare.airplay.AudioFormat
import com.nshare.airplay.AudioPacket
import com.nshare.airplay.AudioSink
import com.nshare.airplay.util.Bytes
import com.nshare.airplay.util.NtpTime
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Receives the RTP audio stream (SETUP stream type 96) over UDP.
 *
 * Data port: RTP packets (PT 0x60), payload AES-128-CBC encrypted per packet (only whole
 * 16-byte blocks are encrypted). AAC-ELD packets are sent redundantly, so duplicates are dropped.
 * Control port: sync packets (0x54) map RTP timestamps to the sender's NTP clock.
 */
class AudioReceiver(
    private val aesKey: ByteArray,
    private val aesIv: ByteArray,
    private val format: AudioFormat,
    private val sink: AudioSink,
) {
    private val dataSocket = DatagramSocket(InetSocketAddress(0))
    private val controlSocket = DatagramSocket(InetSocketAddress(0))
    val dataPort: Int get() = dataSocket.localPort
    val controlPort: Int get() = controlSocket.localPort

    @Volatile private var running = false
    private val threads = ArrayList<Thread>()

    private val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    private val keySpec = SecretKeySpec(aesKey, "AES")

    // RTP → sender clock mapping from the latest sync packet.
    @Volatile private var syncRtp = 0L
    @Volatile private var syncRemoteNs = 0L
    @Volatile private var haveSync = false

    // Reordering / de-duplication state (data thread only).
    private val pending = java.util.TreeMap<Int, ByteArray>()
    private var nextSeq = -1
    private val recent = IntArray(64) { -1 }
    private var recentPos = 0

    @Volatile var packetsReceived = 0L
        private set
    @Volatile var packetsLost = 0L
        private set
    @Volatile var lastDataNs = 0L
        private set

    fun start() {
        running = true
        dataSocket.receiveBufferSize = 512 * 1024
        dataSocket.soTimeout = 500
        controlSocket.soTimeout = 500
        sink.onAudioStart(format)
        threads += Thread(::dataLoop, "nshare-audio-rx").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
        threads += Thread(::controlLoop, "nshare-audio-ctl").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        dataSocket.close()
        controlSocket.close()
        threads.forEach { it.interrupt() }
        threads.clear()
        sink.onAudioStop()
    }

    fun flush() {
        synchronized(pending) {
            pending.clear()
            nextSeq = -1
        }
        sink.onAudioFlush()
    }

    private fun controlLoop() {
        val buf = ByteArray(2048)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try {
                controlSocket.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                break
            }
            val type = buf[1].toInt() and 0x7f
            if (type == 0x54 && p.length >= 20) {
                // [4..7] rtp of the frame due "now" minus sender latency, [8..15] NTP now,
                // [16..19] rtp of the frame captured "now". We map capture time so audio and
                // video share the same (capture) timeline.
                val ntp = Bytes.i64be(buf, 8)
                syncRemoteNs = NtpTime.ntpToNanos(ntp)
                syncRtp = Bytes.u32be(buf, 16)
                if (!haveSync) Log.i(TAG, "first audio sync: latency=${(syncRtp - Bytes.u32be(buf, 4)) and 0xffffffffL} samples")
                haveSync = true
            } else if (type == 0x56 && p.length >= 16) {
                // Retransmitted data packet, starts at offset 4.
                handleData(buf.copyOfRange(4, p.length))
            }
        }
    }

    private fun dataLoop() {
        val buf = ByteArray(4096)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try {
                dataSocket.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                break
            }
            if (p.length < 12) continue
            handleData(buf.copyOf(p.length))
        }
    }

    private fun handleData(packet: ByteArray) {
        val seq = Bytes.u16be(packet, 2)
        synchronized(pending) {
            if (recent.contains(seq)) return
            recent[recentPos] = seq
            recentPos = (recentPos + 1) and (recent.size - 1)
            packetsReceived++
            lastDataNs = System.nanoTime()
            if (nextSeq >= 0 && seqDiff(seq, nextSeq) < 0) return // too late, already skipped
            pending[seq] = packet
            if (nextSeq < 0) nextSeq = seq
            drain()
        }
    }

    private fun drain() {
        while (pending.isNotEmpty()) {
            val p = pending.remove(nextSeq)
            if (p != null) {
                emit(p)
                nextSeq = (nextSeq + 1) and 0xffff
                continue
            }
            // Missing packet: wait for a few more before giving up on it.
            val newest = pending.keys.maxBy { seqDiff(it, nextSeq) }
            if (seqDiff(newest, nextSeq) < REORDER_WINDOW) return
            packetsLost++
            nextSeq = (nextSeq + 1) and 0xffff
        }
    }

    private fun emit(packet: ByteArray) {
        val payloadLen = packet.size - 12
        if (payloadLen <= 0) return
        // Stream start sends "no data" markers instead of audio.
        if (payloadLen == 4 && packet[12].toInt() == 0x00 && packet[13].toInt() == 0x68 &&
            packet[14].toInt() == 0x34 && packet[15].toInt() == 0x00) return
        if (!haveSync) return

        val payload = packet.copyOfRange(12, packet.size)
        val encLen = payloadLen / 16 * 16
        if (encLen > 0) {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(aesIv))
            cipher.doFinal(payload, 0, encLen, payload, 0)
        }
        val rtp = Bytes.u32be(packet, 4)
        val delta = ((rtp - syncRtp).toInt()).toLong() // signed 32-bit wrap
        val remoteNs = syncRemoteNs + delta * 1_000_000_000L / format.sampleRate
        sink.onAudioPacket(AudioPacket(payload, remoteNs, System.nanoTime()))
    }

    private fun seqDiff(a: Int, b: Int): Int = ((a - b + 0x8000) and 0xffff) - 0x8000

    companion object {
        private const val TAG = "NShare.Audio"
        private const val REORDER_WINDOW = 6
    }
}
