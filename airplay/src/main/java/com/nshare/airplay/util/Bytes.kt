package com.nshare.airplay.util

import java.security.MessageDigest

internal object Bytes {
    fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xff

    fun u16be(b: ByteArray, off: Int): Int = (u8(b, off) shl 8) or u8(b, off + 1)

    fun u32be(b: ByteArray, off: Int): Long =
        (u8(b, off).toLong() shl 24) or (u8(b, off + 1).toLong() shl 16) or
            (u8(b, off + 2).toLong() shl 8) or u8(b, off + 3).toLong()

    fun i32be(b: ByteArray, off: Int): Int = u32be(b, off).toInt()

    fun i32le(b: ByteArray, off: Int): Int =
        u8(b, off) or (u8(b, off + 1) shl 8) or (u8(b, off + 2) shl 16) or (u8(b, off + 3) shl 24)

    fun i64be(b: ByteArray, off: Int): Long = (u32be(b, off) shl 32) or u32be(b, off + 4)

    fun i64le(b: ByteArray, off: Int): Long =
        (i32le(b, off).toLong() and 0xffffffffL) or (i32le(b, off + 4).toLong() shl 32)

    fun f32le(b: ByteArray, off: Int): Float = java.lang.Float.intBitsToFloat(i32le(b, off))

    fun put32be(b: ByteArray, off: Int, v: Long) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    fun put64be(b: ByteArray, off: Int, v: Long) {
        put32be(b, off, v ushr 32)
        put32be(b, off + 4, v)
    }

    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hex(b: ByteArray, max: Int = Int.MAX_VALUE): String {
        val n = minOf(b.size, max)
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) {
            val v = b[i].toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * AirPlay timestamps are NTP-style Q32.32 fixed point values. Senders add the
 * 1900→1970 offset to the seconds of timing/sync packets but not to mirror video
 * headers, so everything is normalised to "sender epoch" nanoseconds here.
 */
internal object NtpTime {
    const val SECONDS_1900_TO_1970 = 2208988800L
    private const val NANOS = 1_000_000_000L

    /** Q32.32 without the 1900 offset (mirror video headers) → ns. */
    fun q32ToNanos(q: Long): Long {
        val secs = q ushr 32
        val frac = q and 0xffffffffL
        return secs * NANOS + ((frac * NANOS) ushr 32)
    }

    /** Q32.32 NTP value (timing / sync packets, includes 1900 offset) → ns in the same epoch as video. */
    fun ntpToNanos(q: Long): Long {
        var secs = q ushr 32
        if (secs >= SECONDS_1900_TO_1970) secs -= SECONDS_1900_TO_1970
        val frac = q and 0xffffffffL
        return secs * NANOS + ((frac * NANOS) ushr 32)
    }

    /** Local monotonic ns → Q32.32 (used only as an opaque echo value in timing requests). */
    fun nanosToQ32(ns: Long): Long {
        val secs = ns / NANOS
        val rem = ns % NANOS
        val frac = (rem shl 32) / NANOS
        return (secs shl 32) or (frac and 0xffffffffL)
    }
}
