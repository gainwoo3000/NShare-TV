package com.nshare.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class WireTest {
    @Test
    fun frameRoundTrip() {
        val buf = ByteArrayOutputStream()
        val w = FrameWriter(DataOutputStream(buf))
        val header = VideoConfigCodec.header(Nsp.VideoCodecId.H265, 2400, 1080)
        w.write(Nsp.Type.VIDEO_CONFIG, payload = byteArrayOf(0, 0, 0, 1, 0x40), extra = header)
        w.write(Nsp.Type.VIDEO_FRAME, Nsp.FLAG_KEY_FRAME, 123_456_789_000L, ByteArray(70_000) { it.toByte() })
        val r = FrameReader(DataInputStream(ByteArrayInputStream(buf.toByteArray())))

        val cfg = r.read()
        assertEquals(Nsp.Type.VIDEO_CONFIG, cfg.type)
        val parsed = VideoConfigCodec.parse(cfg.payload)
        assertEquals(Nsp.VideoCodecId.H265, parsed.codec)
        assertEquals(2400, parsed.width)
        assertEquals(1080, parsed.height)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x40), parsed.parameterSets)

        val frame = r.read()
        assertTrue(frame.isKeyFrame)
        assertEquals(123_456_789_000L, frame.timeNs)
        assertEquals(70_000, frame.payload.size)
    }

    @Test
    fun handshakeMessages() {
        val hello = Hello.decode(Hello("Galaxy S25", "SM-S931N", 50123).encode())
        assertEquals("Galaxy S25", hello.deviceName)
        assertEquals(50123, hello.timingPort)
        assertEquals(Nsp.VERSION, hello.protocolVersion)

        val caps = Caps.decode(Caps("NShare-82C9", 3840, 2160, 60, hevc = true).encode())
        assertEquals(3840, caps.maxWidth)
        assertTrue(caps.hevc)

        val audio = AudioConfigCodec.parse(AudioConfigCodec.header(Nsp.AudioCodecId.AAC, 48000, 2) + byteArrayOf(0x11, 0x90.toByte()))
        assertEquals(48000, audio.sampleRate)
        assertEquals(2, audio.channels)
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), audio.csd)
    }

    @Test
    fun timingEncodingMatchesReceiverConvention() {
        val ns = 98_765_432_123L
        val q = TimingResponder.toNtp(ns)
        val secs = (q ushr 32) - 2208988800L
        val frac = q and 0xffffffffL
        val back = secs * 1_000_000_000L + ((frac * 1_000_000_000L) ushr 32)
        assertTrue(kotlin.math.abs(back - ns) < 2)
    }
}
