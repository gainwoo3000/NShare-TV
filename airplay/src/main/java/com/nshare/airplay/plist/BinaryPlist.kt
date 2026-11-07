package com.nshare.airplay.plist

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal binary property list ("bplist00") codec covering what AirPlay uses.
 *
 * Decoded types: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, ByteArray, null.
 * 128-bit integers (used by Apple for unsigned 64-bit values above Long.MAX_VALUE) decode to
 * their low 64 bits, so callers needing unsigned semantics should use [java.lang.Long.toUnsignedString].
 */
object BinaryPlist {
    private val MAGIC = "bplist00".toByteArray(StandardCharsets.US_ASCII)

    class FormatException(msg: String) : Exception(msg)

    fun isBinaryPlist(data: ByteArray?): Boolean =
        data != null && data.size >= 40 && MAGIC.indices.all { data[it] == MAGIC[it] }

    // ---------------------------------------------------------------- decoding

    fun decode(data: ByteArray): Any? {
        if (!isBinaryPlist(data)) throw FormatException("not a bplist00")
        val t = data.size - 32
        val offsetSize = data[t + 6].toInt() and 0xff
        val refSize = data[t + 7].toInt() and 0xff
        val numObjects = readUInt(data, t + 8, 8).toInt()
        val topObject = readUInt(data, t + 16, 8).toInt()
        val tableOffset = readUInt(data, t + 24, 8).toInt()
        if (offsetSize !in 1..8 || refSize !in 1..8 || numObjects <= 0 || tableOffset >= t) {
            throw FormatException("bad trailer")
        }
        val offsets = IntArray(numObjects) { readUInt(data, tableOffset + it * offsetSize, offsetSize).toInt() }
        return Decoder(data, offsets, refSize).obj(topObject, 0)
    }

    private class Decoder(val d: ByteArray, val offsets: IntArray, val refSize: Int) {
        fun obj(ref: Int, depth: Int): Any? {
            if (depth > 32) throw FormatException("too deep")
            if (ref !in offsets.indices) throw FormatException("bad ref $ref")
            var p = offsets[ref]
            val marker = d[p].toInt() and 0xff
            val hi = marker ushr 4
            val lo = marker and 0x0f
            p++
            return when (hi) {
                0x0 -> when (lo) {
                    0x8 -> false
                    0x9 -> true
                    else -> null
                }
                0x1 -> {
                    val n = 1 shl lo
                    if (n == 16) readUInt(d, p + 8, 8) // low 64 bits of a 128-bit int
                    else if (n == 8) readUInt(d, p, 8)
                    else readUInt(d, p, n)
                }
                0x2 -> when (lo) {
                    2 -> java.lang.Float.intBitsToFloat(readUInt(d, p, 4).toInt()).toDouble()
                    3 -> java.lang.Double.longBitsToDouble(readUInt(d, p, 8))
                    else -> throw FormatException("bad real")
                }
                0x3 -> java.lang.Double.longBitsToDouble(readUInt(d, p, 8)) // date: seconds since 2001
                0x4 -> {
                    val (len, start) = length(lo, p)
                    d.copyOfRange(start, start + len)
                }
                0x5 -> {
                    val (len, start) = length(lo, p)
                    String(d, start, len, StandardCharsets.US_ASCII)
                }
                0x6 -> {
                    val (len, start) = length(lo, p)
                    String(d, start, len * 2, StandardCharsets.UTF_16BE)
                }
                0x8 -> readUInt(d, p, lo + 1)
                0xA -> {
                    val (count, start) = length(lo, p)
                    List(count) { obj(readUInt(d, start + it * refSize, refSize).toInt(), depth + 1) }
                }
                0xD -> {
                    val (count, start) = length(lo, p)
                    val map = LinkedHashMap<String, Any?>(count)
                    for (i in 0 until count) {
                        val k = obj(readUInt(d, start + i * refSize, refSize).toInt(), depth + 1)
                        val v = obj(readUInt(d, start + (count + i) * refSize, refSize).toInt(), depth + 1)
                        map[k.toString()] = v
                    }
                    map
                }
                else -> throw FormatException("unsupported marker 0x${marker.toString(16)}")
            }
        }

        /** Returns (length, payloadStart) for a marker whose low nibble may be 0xF (extended length). */
        fun length(lo: Int, p: Int): Pair<Int, Int> {
            if (lo != 0x0f) return lo to p
            val m = d[p].toInt() and 0xff
            if (m ushr 4 != 0x1) throw FormatException("bad length marker")
            val n = 1 shl (m and 0x0f)
            return readUInt(d, p + 1, n).toInt() to (p + 1 + n)
        }
    }

    private fun readUInt(d: ByteArray, off: Int, n: Int): Long {
        if (off < 0 || off + n > d.size) throw FormatException("truncated")
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (d[off + i].toLong() and 0xff)
        return v
    }

    // ---------------------------------------------------------------- encoding

    fun encode(root: Any?): ByteArray {
        val objects = ArrayList<Any?>()
        flatten(root, objects)
        val refSize = if (objects.size < 256) 1 else 2
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        val offsets = IntArray(objects.size)
        // Objects were flattened depth-first; children refs are assigned in the same order.
        val refs = HashMap<Int, IntArray>()
        var next = 0
        fun assign(o: Any?): Int {
            val id = next++
            when (o) {
                is Map<*, *> -> {
                    val keyIds = o.keys.map { assign(it.toString()) }
                    val valIds = o.values.map { assign(it) }
                    refs[id] = (keyIds + valIds).toIntArray()
                }
                is List<*> -> refs[id] = o.map { assign(it) }.toIntArray()
                is Array<*> -> refs[id] = o.map { assign(it) }.toIntArray()
            }
            return id
        }
        assign(root)
        for ((i, o) in objects.withIndex()) {
            offsets[i] = out.size()
            writeObject(out, o, refs[i], refSize)
        }
        val tableOffset = out.size()
        val offsetSize = when {
            tableOffset < 0x100 -> 1
            tableOffset < 0x10000 -> 2
            else -> 4
        }
        for (o in offsets) writeUInt(out, o.toLong(), offsetSize)
        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        putLong(trailer, 8, objects.size.toLong())
        putLong(trailer, 16, 0)
        putLong(trailer, 24, tableOffset.toLong())
        out.write(trailer)
        return out.toByteArray()
    }

    private fun flatten(o: Any?, list: MutableList<Any?>) {
        list.add(o)
        when (o) {
            is Map<*, *> -> {
                o.keys.forEach { flatten(it.toString(), list) }
                o.values.forEach { flatten(it, list) }
            }
            is List<*> -> o.forEach { flatten(it, list) }
            is Array<*> -> o.forEach { flatten(it, list) }
        }
    }

    private fun writeObject(out: ByteArrayOutputStream, o: Any?, children: IntArray?, refSize: Int) {
        when (o) {
            null -> out.write(0x00)
            is Boolean -> out.write(if (o) 0x09 else 0x08)
            is Int, is Long, is Short, is Byte -> writeInt(out, (o as Number).toLong())
            is Float, is Double -> {
                out.write(0x23)
                writeUInt(out, java.lang.Double.doubleToLongBits((o as Number).toDouble()), 8)
            }
            is ByteArray -> {
                writeMarker(out, 0x40, o.size)
                out.write(o)
            }
            is String -> {
                val ascii = o.all { it.code < 0x80 }
                if (ascii) {
                    writeMarker(out, 0x50, o.length)
                    out.write(o.toByteArray(StandardCharsets.US_ASCII))
                } else {
                    writeMarker(out, 0x60, o.length)
                    out.write(o.toByteArray(StandardCharsets.UTF_16BE))
                }
            }
            is Map<*, *> -> {
                writeMarker(out, 0xD0, o.size)
                children!!.forEach { writeUInt(out, it.toLong(), refSize) }
            }
            is List<*>, is Array<*> -> {
                writeMarker(out, 0xA0, children!!.size)
                children.forEach { writeUInt(out, it.toLong(), refSize) }
            }
            else -> throw IllegalArgumentException("unsupported plist type ${o::class.java}")
        }
    }

    private fun writeMarker(out: ByteArrayOutputStream, type: Int, len: Int) {
        if (len < 15) {
            out.write(type or len)
        } else {
            out.write(type or 0x0f)
            writeInt(out, len.toLong())
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Long) {
        when {
            v < 0 -> { out.write(0x13); writeUInt(out, v, 8) }
            v <= 0xff -> { out.write(0x10); writeUInt(out, v, 1) }
            v <= 0xffff -> { out.write(0x11); writeUInt(out, v, 2) }
            v <= 0xffffffffL -> { out.write(0x12); writeUInt(out, v, 4) }
            else -> { out.write(0x13); writeUInt(out, v, 8) }
        }
    }

    private fun writeUInt(out: ByteArrayOutputStream, v: Long, n: Int) {
        for (i in n - 1 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xff)
    }

    private fun putLong(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (8 * (7 - i))).toByte()
    }
}

/** Convenience accessors for decoded plist dictionaries. */
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.dict(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
internal fun Map<String, Any?>.list(key: String): List<Any?>? = this[key] as? List<Any?>
internal fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
internal fun Map<String, Any?>.string(key: String): String? = this[key] as? String
internal fun Map<String, Any?>.data(key: String): ByteArray? = this[key] as? ByteArray
internal fun Map<String, Any?>.bool(key: String): Boolean? = when (val v = this[key]) {
    is Boolean -> v
    is Number -> v.toLong() != 0L
    else -> null
}
