package com.nshare.airplay.server

/**
 * DMAP ("Digital Media Access Protocol") as used by AirPlay track metadata: a sequence of
 * 4-byte tag, 4-byte big-endian length, value. Containers (e.g. "mlit") hold further items.
 * Returns the string items, e.g. minm = title, asar = artist, asal = album.
 */
object Dmap {
    private val CONTAINERS = setOf("mlit", "mlcl", "mcon", "cmst", "mdcl", "msrv", "mccr")
    private val STRINGS = setOf("minm", "asar", "asal", "asaa", "asgn", "ascp", "ascm")

    fun parse(data: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        parse(data, 0, data.size, out, depth = 0)
        return out
    }

    private fun parse(d: ByteArray, from: Int, to: Int, out: MutableMap<String, String>, depth: Int) {
        var i = from
        while (i + 8 <= to) {
            val tag = String(d, i, 4, Charsets.US_ASCII)
            val len = ((d[i + 4].toInt() and 0xff) shl 24) or ((d[i + 5].toInt() and 0xff) shl 16) or
                ((d[i + 6].toInt() and 0xff) shl 8) or (d[i + 7].toInt() and 0xff)
            val start = i + 8
            if (len < 0 || start + len > to) return
            when {
                tag in CONTAINERS && depth < 4 -> parse(d, start, start + len, out, depth + 1)
                tag in STRINGS -> out[tag] = String(d, start, len, Charsets.UTF_8)
            }
            i = start + len
        }
    }
}
