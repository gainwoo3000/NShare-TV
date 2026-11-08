package com.nshare.airplay.audio

/**
 * Apple Lossless (ALAC) decoder for 16-bit mono / stereo, as sent by AirPlay audio (Apple Music
 * and other apps stream ALAC 44.1 kHz / 16 bit / 2 ch, 352 frames per packet).
 *
 * A Kotlin port of the decoding path of Apple's open-source ALAC reference implementation
 * (Apache License 2.0): adaptive Golomb entropy decoding (ag_dec), the adaptive predictor
 * (dp_dec) and stereo un-mixing (matrix_dec). Verified bit-exact against `afconvert` output.
 */
class AlacDecoder(private val config: Config) {
    /** ALACSpecificConfig. AirPlay's fmtp "96 352 0 16 40 10 14 2 255 0 0 44100" is [AIRPLAY]. */
    data class Config(
        val frameLength: Int,
        val bitDepth: Int = 16,
        val pb: Int = 40,
        val mb: Int = 10,
        val kb: Int = 14,
        val numChannels: Int = 2,
        val maxRun: Int = 255,
        val sampleRate: Int = 44_100,
    ) {
        companion object {
            val AIRPLAY = Config(frameLength = 352)

            /** Parses the 24-byte ALACSpecificConfig ("magic cookie"). */
            fun parse(b: ByteArray, off: Int = 0): Config {
                fun u8(i: Int) = b[off + i].toInt() and 0xff
                fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
                fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
                return Config(
                    frameLength = u32(0), bitDepth = u8(5), pb = u8(6), mb = u8(7), kb = u8(8),
                    numChannels = u8(9), maxRun = u16(10), sampleRate = u32(20),
                )
            }
        }
    }

    class FormatException(message: String) : RuntimeException(message)

    init {
        require(config.bitDepth == 16) { "only 16-bit ALAC is supported" }
        require(config.numChannels in 1..2) { "only mono / stereo ALAC is supported" }
    }

    private val predictor = IntArray(config.frameLength)
    private val mixU = IntArray(config.frameLength)
    private val mixV = IntArray(config.frameLength)
    private val coefsU = IntArray(32)
    private val coefsV = IntArray(32)

    /**
     * Decodes one packet into interleaved 16-bit samples. Returns the number of frames written to
     * [out] (which must hold frameLength * channels samples).
     */
    fun decode(packet: ByteArray, length: Int, out: ShortArray): Int {
        // The bit reader may look up to 8 bytes past the data.
        val buf = ByteArray(length + 8)
        System.arraycopy(packet, 0, buf, 0, length)
        val bits = BitReader(buf, length)
        var channelIndex = 0
        var frames = 0
        while (true) {
            when (bits.read(3)) {
                ID_SCE, ID_LFE -> {
                    if (channelIndex + 1 > config.numChannels) return frames
                    frames = decodeSingle(bits, out, channelIndex)
                    channelIndex += 1
                }
                ID_CPE -> {
                    if (channelIndex + 2 > config.numChannels) return frames
                    frames = decodePair(bits, out, channelIndex)
                    channelIndex += 2
                }
                ID_DSE -> {
                    bits.read(4) // element instance tag
                    val align = bits.read(1)
                    var count = bits.read(8)
                    if (count == 255) count += bits.read(8)
                    if (align != 0) bits.byteAlign()
                    bits.advance(count * 8)
                }
                ID_FIL -> {
                    var count = bits.read(4)
                    if (count == 15) count += bits.read(8) - 1
                    bits.advance(count * 8)
                }
                ID_END -> return frames
                else -> throw FormatException("unsupported ALAC element")
            }
            if (bits.position > bits.limitBits) throw FormatException("ALAC packet overrun")
        }
    }

    private fun decodeSingle(bits: BitReader, out: ShortArray, channel: Int): Int {
        bits.read(4) // element instance tag
        if (bits.read(12) != 0) throw FormatException("bad ALAC header")
        val header = bits.read(4)
        val partial = header ushr 3
        val bytesShifted = (header ushr 1) and 3
        val escape = header and 1
        if (bytesShifted != 0) throw FormatException("shifted samples are not used with 16-bit audio")
        var numSamples = config.frameLength
        if (partial != 0) numSamples = (bits.read(16) shl 16) or bits.read(16)
        if (numSamples > config.frameLength) throw FormatException("frame too long")
        if (escape == 0) {
            val chanBits = config.bitDepth
            bits.read(8) // mixBits (unused for one channel)
            bits.read(8) // mixRes
            val p = readChannelParams(bits, coefsU)
            decompress(bits, numSamples, chanBits, p, coefsU, mixU)
        } else {
            readUncompressed(bits, numSamples, mixU, null)
        }
        var o = channel
        for (i in 0 until numSamples) {
            out[o] = mixU[i].toShort()
            o += config.numChannels
        }
        return numSamples
    }

    private fun decodePair(bits: BitReader, out: ShortArray, channel: Int): Int {
        bits.read(4) // element instance tag
        if (bits.read(12) != 0) throw FormatException("bad ALAC header")
        val header = bits.read(4)
        val partial = header ushr 3
        val bytesShifted = (header ushr 1) and 3
        val escape = header and 1
        if (bytesShifted != 0) throw FormatException("shifted samples are not used with 16-bit audio")
        var numSamples = config.frameLength
        if (partial != 0) numSamples = (bits.read(16) shl 16) or bits.read(16)
        if (numSamples > config.frameLength) throw FormatException("frame too long")
        var mixBits = 0
        var mixRes = 0
        if (escape == 0) {
            // One extra bit: the mid/side channels need it.
            val chanBits = config.bitDepth + 1
            mixBits = bits.read(8)
            mixRes = bits.read(8).toByte().toInt()
            val pU = readChannelParams(bits, coefsU)
            val pV = readChannelParams(bits, coefsV)
            decompress(bits, numSamples, chanBits, pU, coefsU, mixU)
            decompress(bits, numSamples, chanBits, pV, coefsV, mixV)
        } else {
            readUncompressed(bits, numSamples, mixU, mixV)
        }
        // unmix16
        var o = channel
        val stride = config.numChannels
        if (mixRes != 0) {
            for (j in 0 until numSamples) {
                val l = mixU[j] + mixV[j] - ((mixRes * mixV[j]) shr mixBits)
                val r = l - mixV[j]
                out[o] = l.toShort()
                out[o + 1] = r.toShort()
                o += stride
            }
        } else {
            for (j in 0 until numSamples) {
                out[o] = mixU[j].toShort()
                out[o + 1] = mixV[j].toShort()
                o += stride
            }
        }
        return numSamples
    }

    /** mode, denShift, pbFactor, number of coefficients (the coefficients go into [coefs]). */
    private class ChannelParams(val mode: Int, val denShift: Int, val pbFactor: Int, val numCoefs: Int)

    private fun readChannelParams(bits: BitReader, coefs: IntArray): ChannelParams {
        val h1 = bits.read(8)
        val h2 = bits.read(8)
        val num = h2 and 0x1f
        for (i in 0 until num) coefs[i] = bits.read(16).toShort().toInt()
        return ChannelParams(h1 ushr 4, h1 and 0xf, h2 ushr 5, num)
    }

    private fun readUncompressed(bits: BitReader, numSamples: Int, u: IntArray, v: IntArray?) {
        val chanBits = config.bitDepth
        val shift = 32 - chanBits
        for (i in 0 until numSamples) {
            u[i] = (bits.read(chanBits) shl shift) shr shift
            if (v != null) v[i] = (bits.read(chanBits) shl shift) shr shift
        }
    }

    private fun decompress(bits: BitReader, numSamples: Int, chanBits: Int, p: ChannelParams, coefs: IntArray, out: IntArray) {
        dynDecomp(bits, numSamples, chanBits, (config.pb * p.pbFactor) / 4)
        if (p.mode == 0) {
            unpcBlock(predictor, out, numSamples, coefs, p.numCoefs, chanBits, p.denShift)
        } else {
            // Mode 15: a first-order pass in place, then the regular predictor.
            unpcBlock(predictor, predictor, numSamples, null, 31, chanBits, 0)
            unpcBlock(predictor, out, numSamples, coefs, p.numCoefs, chanBits, p.denShift)
        }
    }

    // ------------------------------------------------------------------ adaptive Golomb (ag_dec)

    private fun dynDecomp(bits: BitReader, numSamples: Int, maxSize: Int, pb: Int) {
        val kb = config.kb
        val wb = (1 shl kb) - 1
        val inBuf = bits.data
        var bitPos = bits.position
        val maxPos = bits.limitBits
        var mb = config.mb.toLong() // unsigned 32-bit in the reference
        var zmode = 0L
        var c = 0
        while (c < numSamples) {
            if (bitPos >= maxPos) throw FormatException("ALAC data ends early")
            var m = (mb ushr QBSHIFT).toInt()
            var k = lg3a(m)
            k = minOf(k, kb)
            m = (1 shl k) - 1
            // dyn_get_32bit
            var streamLong = read32(inBuf, bitPos ushr 3) shl (bitPos and 7)
            var n: Long
            val prefix = Integer.numberOfLeadingZeros(streamLong.inv())
            if (prefix >= MAX_PREFIX_32) {
                n = streamBits(inBuf, bitPos + MAX_PREFIX_32, maxSize).toLong() and 0xffffffffL
                bitPos += MAX_PREFIX_32 + maxSize
            } else {
                n = prefix.toLong()
                bitPos += prefix + 1
                if (k != 1) {
                    streamLong = streamLong shl (prefix + 1)
                    val v = (streamLong ushr (32 - k)).toLong() and 0xffffffffL
                    bitPos += k - 1
                    n *= m
                    if (v >= 2) {
                        n += v - 1
                        bitPos += 1
                    }
                }
            }
            n = n and 0xffffffffL
            // Least significant bit is the sign.
            val nDecode = (n + zmode) and 0xffffffffL
            val magnitude = ((nDecode + 1) ushr 1).toInt()
            predictor[c] = if (nDecode and 1L != 0L) -magnitude else magnitude
            c++
            mb = (pb * (n + zmode) + mb - ((pb * mb) ushr QBSHIFT)) and 0xffffffffL
            if (n > N_MAX_MEAN_CLAMP) mb = N_MEAN_CLAMP_VAL
            zmode = 0
            if ((mb shl MMULSHIFT) < QB && c < numSamples) {
                zmode = 1
                val kz = Integer.numberOfLeadingZeros(mb.toInt()) - BITOFF + ((mb + MOFF) ushr MDENSHIFT).toInt()
                val mz = ((1 shl kz) - 1) and wb
                // dyn_get
                var sl = read32(inBuf, bitPos ushr 3) shl (bitPos and 7)
                var pre = Integer.numberOfLeadingZeros(sl.inv())
                val run: Int
                if (pre >= MAX_PREFIX_16) {
                    pre = MAX_PREFIX_16
                    bitPos += pre
                    sl = sl shl pre
                    run = sl ushr (32 - MAX_DATATYPE_BITS_16)
                    bitPos += MAX_DATATYPE_BITS_16
                } else {
                    bitPos += pre + 1
                    sl = sl shl (pre + 1)
                    val v = if (kz == 0) 0 else sl ushr (32 - kz)
                    bitPos += kz
                    var r = pre * mz + v - 1
                    if (v < 2) {
                        r -= v - 1
                        bitPos -= 1
                    }
                    run = r
                }
                if (c + run > numSamples) throw FormatException("ALAC zero run too long")
                for (j in 0 until run) predictor[c++] = 0
                if (run >= 65535) zmode = 0
                mb = 0
            }
        }
        bits.position = bitPos
    }

    private fun lg3a(x: Int): Int = 31 - Integer.numberOfLeadingZeros(x + 3)

    private fun read32(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or
            ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)

    /** getstreambits: [numBits] (≤ 32) bits starting at [bitOffset]. */
    private fun streamBits(b: ByteArray, bitOffset: Int, numBits: Int): Int {
        val byteOffset = bitOffset ushr 3
        val sub = bitOffset and 7
        val load1 = read32(b, byteOffset)
        var result: Int
        if (numBits + sub > 32) {
            result = load1 shl sub
            var load2 = b[byteOffset + 4].toInt() and 0xff
            load2 = load2 ushr (8 - (numBits + sub - 32))
            result = result ushr (32 - numBits)
            result = result or load2
        } else {
            result = load1 ushr (32 - numBits - sub)
        }
        if (numBits != 32) result = result and ((1 shl numBits) - 1)
        return result
    }

    // ------------------------------------------------------------------ adaptive predictor (dp_dec)

    private fun unpcBlock(pc1: IntArray, out: IntArray, num: Int, coefs: IntArray?, numActive: Int, chanBits: Int, denShift: Int) {
        val chanShift = 32 - chanBits
        val denHalf = if (denShift == 0) 0 else 1 shl (denShift - 1)
        out[0] = pc1[0]
        if (numActive == 0) {
            if (num > 1) System.arraycopy(pc1, 1, out, 1, num - 1)
            return
        }
        if (numActive == 31) {
            var prev = out[0]
            for (j in 1 until num) {
                val del = pc1[j] + prev
                prev = (del shl chanShift) shr chanShift
                out[j] = prev
            }
            return
        }
        coefs!!
        for (j in 1..minOf(numActive, num - 1)) {
            val del = pc1[j] + out[j - 1]
            out[j] = (del shl chanShift) shr chanShift
        }
        val lim = numActive + 1
        for (j in lim until num) {
            var sum1 = 0
            val top = out[j - lim]
            val base = j - 1
            for (k in 0 until numActive) sum1 += coefs[k] * (out[base - k] - top)
            var del = pc1[j]
            var del0 = del
            val sg = signOf(del)
            del += top + ((sum1 + denHalf) shr denShift)
            out[j] = (del shl chanShift) shr chanShift
            if (sg > 0) {
                for (k in numActive - 1 downTo 0) {
                    val dd = top - out[base - k]
                    val sgn = signOf(dd)
                    coefs[k] = (coefs[k] - sgn).toShort().toInt()
                    del0 -= (numActive - k) * ((sgn * dd) shr denShift)
                    if (del0 <= 0) break
                }
            } else if (sg < 0) {
                for (k in numActive - 1 downTo 0) {
                    val dd = top - out[base - k]
                    val sgn = signOf(dd)
                    coefs[k] = (coefs[k] + sgn).toShort().toInt()
                    del0 -= (numActive - k) * ((-sgn * dd) shr denShift)
                    if (del0 >= 0) break
                }
            }
        }
    }

    private fun signOf(i: Int): Int = if (i > 0) 1 else if (i < 0) -1 else 0

    /** MSB-first bit reader over [data]; [limitBits] marks the end of the real packet data. */
    private class BitReader(val data: ByteArray, length: Int) {
        var position = 0
        val limitBits = length * 8

        fun read(n: Int): Int {
            if (n == 0) return 0
            var v = 0L
            repeat(n) {
                val byte = data[position ushr 3].toInt() and 0xff
                v = (v shl 1) or ((byte ushr (7 - (position and 7))) and 1).toLong()
                position++
            }
            return v.toInt()
        }

        fun advance(n: Int) {
            position += n
        }

        fun byteAlign() {
            position = (position + 7) and 7.inv()
        }
    }

    companion object {
        private const val ID_SCE = 0
        private const val ID_CPE = 1
        private const val ID_LFE = 3
        private const val ID_DSE = 4
        private const val ID_FIL = 6
        private const val ID_END = 7

        private const val QBSHIFT = 9
        private const val QB = 1L shl QBSHIFT
        private const val MMULSHIFT = 2
        private const val MDENSHIFT = QBSHIFT - MMULSHIFT - 1
        private const val MOFF = 1L shl (MDENSHIFT - 2)
        private const val BITOFF = 24
        private const val MAX_PREFIX_16 = 9
        private const val MAX_PREFIX_32 = 9
        private const val MAX_DATATYPE_BITS_16 = 16
        private const val N_MAX_MEAN_CLAMP = 0xffffL
        private const val N_MEAN_CLAMP_VAL = 0xffffL
    }
}
