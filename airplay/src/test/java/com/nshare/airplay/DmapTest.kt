package com.nshare.airplay

import com.nshare.airplay.server.Dmap
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class DmapTest {
    private fun item(tag: String, value: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(tag.toByteArray())
        val n = value.size
        o.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        o.write(value)
        return o.toByteArray()
    }

    @Test
    fun parsesTrackInfoInsideContainer() {
        val body = item(
            "mlit",
            item("miid", byteArrayOf(0, 0, 0, 7)) + item("minm", "밤양갱".toByteArray()) +
                item("asar", "비비 (BIBI)".toByteArray()) + item("asal", "밤양갱 - Single".toByteArray()) +
                item("astm", byteArrayOf(0, 2, 0x3a, 0x10)),
        )
        val tags = Dmap.parse(body)
        assertEquals("밤양갱", tags["minm"])
        assertEquals("비비 (BIBI)", tags["asar"])
        assertEquals("밤양갱 - Single", tags["asal"])
    }

    @Test
    fun truncatedInputIsIgnored() {
        val body = item("minm", "Song".toByteArray()).copyOf(9)
        assertEquals(emptyMap<String, String>(), Dmap.parse(body))
    }
}
