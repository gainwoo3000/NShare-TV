package com.nshare.airplay

import com.nshare.airplay.audio.AlacDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lossless means bit-exact: packets encoded by macOS `afconvert -d alac` (see resources/alac) must
 * decode to exactly the original PCM.
 */
class AlacDecoderTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("alac/$name")!!.readBytes()

    private fun check(name: String) {
        val input = DataInputStream(resource("$name.alac").inputStream())
        val cookie = ByteArray(24).also { input.readFully(it) }
        val config = AlacDecoder.Config.parse(cookie)
        val packets = input.readInt()
        val validFrames = input.readInt()
        val decoder = AlacDecoder(config)
        val pcm = ShortArray(validFrames * config.numChannels + config.frameLength * config.numChannels)
        val frame = ShortArray(config.frameLength * config.numChannels)
        var frames = 0
        repeat(packets) {
            val p = ByteArray(input.readInt()).also { input.readFully(it) }
            val n = decoder.decode(p, p.size, frame)
            System.arraycopy(frame, 0, pcm, frames * config.numChannels, n * config.numChannels)
            frames += n
        }
        assertEquals("$name frames", validFrames, frames)
        val expected = ByteBuffer.wrap(resource("$name.pcm")).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until expected.limit()) {
            if (expected[i] != pcm[i]) throw AssertionError("$name: sample $i expected ${expected[i]} got ${pcm[i]}")
        }
    }

    @Test
    fun stereoIsBitExact() = check("st")

    @Test
    fun monoIsBitExact() = check("mo")

    @Test
    fun airPlayDefaultConfig() {
        val c = AlacDecoder.Config.AIRPLAY
        assertEquals(352, c.frameLength)
        assertEquals(2, c.numChannels)
        assertEquals(44_100, c.sampleRate)
    }
}
