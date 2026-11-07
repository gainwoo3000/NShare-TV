package com.nshare.airplay.time

import android.util.Log
import com.nshare.airplay.util.Bytes
import com.nshare.airplay.util.NtpTime
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * AirPlay 1 timing: the receiver periodically sends 32-byte NTP-like requests (type 0x52)
 * to the sender's timing port; the sender answers with type 0x53 carrying its receive and
 * transmit timestamps. We also answer requests the sender may send to us.
 */
class NtpClient(
    private val remote: InetAddress,
    private val remotePort: Int,
    private val clock: ClockSync,
) {
    private val socket = DatagramSocket(InetSocketAddress(0))
    val localPort: Int get() = socket.localPort

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        socket.soTimeout = 200
        thread = Thread(::loop, "nshare-ntp").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        socket.close()
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        val request = ByteArray(32)
        request[0] = 0x80.toByte()
        request[1] = 0xd2.toByte()
        request[3] = 0x07
        val buf = ByteArray(128)
        val target = InetSocketAddress(remote, remotePort)
        var sent = 0
        var lastSendNs = 0L
        var lastSentQ = 0L
        var lastSentNs = 0L
        while (running) {
            val now = System.nanoTime()
            // Burst quickly at session start so the clock converges before media flows.
            val interval = if (sent < 6) 150_000_000L else 1_000_000_000L
            if (remotePort > 0 && now - lastSendNs >= interval) {
                lastSentNs = System.nanoTime()
                lastSentQ = NtpTime.nanosToQ32(lastSentNs)
                Bytes.put64be(request, 24, lastSentQ)
                try {
                    socket.send(DatagramPacket(request, request.size, target))
                    sent++
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "timing send failed: ${e.message}")
                }
                lastSendNs = now
            }
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running) Log.w(TAG, "timing receive failed: ${e.message}")
                break
            }
            val t4 = System.nanoTime()
            if (packet.length < 32) continue
            when (buf[1].toInt() and 0x7f) {
                0x53 -> {
                    val origin = Bytes.i64be(buf, 8)
                    if (origin != lastSentQ) continue // stale or duplicate reply
                    val t1 = lastSentNs
                    val t2 = NtpTime.ntpToNanos(Bytes.i64be(buf, 16))
                    val t3 = NtpTime.ntpToNanos(Bytes.i64be(buf, 24))
                    val offset = ((t2 - t1) + (t3 - t4)) / 2
                    val delay = (t4 - t1) - (t3 - t2)
                    clock.addNtpSample(offset, delay.coerceAtLeast(0))
                }
                0x52 -> replyToRequest(buf, packet)
            }
        }
    }

    private fun replyToRequest(req: ByteArray, packet: DatagramPacket) {
        val res = ByteArray(32)
        res[0] = 0x80.toByte()
        res[1] = 0xd3.toByte()
        res[3] = 0x07
        System.arraycopy(req, 24, res, 8, 8)
        // Answer in the sender-epoch-with-1900-offset convention used by timing packets.
        val now = clock.localToRemote(System.nanoTime())
        val q = NtpTime.nanosToQ32(now) + (NtpTime.SECONDS_1900_TO_1970 shl 32)
        Bytes.put64be(res, 16, q)
        Bytes.put64be(res, 24, q)
        try {
            socket.send(DatagramPacket(res, res.size, packet.socketAddress))
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "NShare.Ntp"
    }
}
