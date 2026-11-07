package com.nshare.protocol

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Sender side of the clock sync: answers the receiver's NTP-style timing requests (type 0x52)
 * with System.nanoTime(), encoded as Q32.32 with the NTP 1900 epoch offset added to the seconds
 * (the convention the receiver's AirPlay timing client expects).
 */
class TimingResponder {
    private val socket = DatagramSocket(InetSocketAddress(0)).apply { soTimeout = 500 }
    val port: Int get() = socket.localPort
    @Volatile private var running = true

    private val thread = Thread({
        val buf = ByteArray(128)
        while (running) {
            val p = DatagramPacket(buf, buf.size)
            try {
                socket.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            val received = System.nanoTime()
            if (p.length < 32 || (buf[1].toInt() and 0x7f) != 0x52) continue
            val res = ByteArray(32)
            res[0] = 0x80.toByte()
            res[1] = 0xd3.toByte()
            res[3] = 0x07
            System.arraycopy(buf, 24, res, 8, 8) // origin = their transmit time
            put64(res, 16, toNtp(received))
            put64(res, 24, toNtp(System.nanoTime()))
            try {
                socket.send(DatagramPacket(res, res.size, p.socketAddress))
            } catch (_: Exception) {
            }
        }
    }, "nshare-timing").apply {
        isDaemon = true
        start()
    }

    fun close() {
        running = false
        socket.close()
    }

    companion object {
        private const val SECONDS_1900_TO_1970 = 2208988800L

        fun toNtp(ns: Long): Long {
            val secs = ns / 1_000_000_000L + SECONDS_1900_TO_1970
            val frac = ((ns % 1_000_000_000L) shl 32) / 1_000_000_000L
            return (secs shl 32) or (frac and 0xffffffffL)
        }

        private fun put64(b: ByteArray, off: Int, v: Long) {
            for (i in 0 until 8) b[off + i] = (v ushr (56 - 8 * i)).toByte()
        }
    }
}
