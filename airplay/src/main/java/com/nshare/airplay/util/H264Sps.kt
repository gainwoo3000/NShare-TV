package com.nshare.airplay.util

/**
 * Rewrites an H.264 SPS so the decoder may output every frame immediately.
 *
 * Senders such as macOS emit SPS without VUI bitstream_restriction. A conforming decoder then
 * has to assume the maximum DPB size for the level (up to 16 frames) and holds that many frames
 * before outputting, which adds hundreds of milliseconds. We set max_num_reorder_frames = 0 and
 * max_dec_frame_buffering = max_num_ref_frames, which is correct for streams without B-frames
 * (screen mirroring encoders never use them).
 */
object H264Sps {
    class Info(val profileIdc: Int, val levelIdc: Int, val maxNumRefFrames: Int, val maxNumReorderFrames: Int?)

    /** [nal] is a single SPS NAL unit without start code (first byte = NAL header 0x67 etc). */
    fun patchLowLatency(nal: ByteArray): ByteArray {
        val rbsp = unescape(nal, 1)
        val r = BitReader(rbsp)
        val sps = parseUpToVui(r)
        val vuiFlagPos = r.pos
        val w = BitWriter()
        if (r.u(1) == 1) {
            val restrictionFlagPos = skipVuiUntilRestriction(r)
            w.copyBits(rbsp, 0, restrictionFlagPos)
        } else {
            w.copyBits(rbsp, 0, vuiFlagPos)
            w.u(1, 1) // vui_parameters_present_flag
            repeat(5) { w.u(0, 1) } // aspect, overscan, video_signal, chroma_loc, timing
            w.u(0, 1) // nal_hrd
            w.u(0, 1) // vcl_hrd
            w.u(0, 1) // pic_struct_present
        }
        w.u(1, 1) // bitstream_restriction_flag
        w.u(1, 1) // motion_vectors_over_pic_boundaries_flag
        w.ue(2) // max_bytes_per_pic_denom
        w.ue(1) // max_bits_per_mb_denom
        w.ue(16) // log2_max_mv_length_horizontal
        w.ue(16) // log2_max_mv_length_vertical
        w.ue(0) // max_num_reorder_frames
        w.ue(maxOf(1, sps.maxNumRefFrames)) // max_dec_frame_buffering
        w.u(1, 1) // rbsp_stop_one_bit
        w.alignZero()
        return byteArrayOf(nal[0]) + escape(w.toByteArray())
    }

    fun parse(nal: ByteArray): Info {
        val r = BitReader(unescape(nal, 1))
        val base = parseUpToVui(r)
        var reorder: Int? = null
        if (r.u(1) == 1) {
            skipVuiUntilRestriction(r)
            if (r.u(1) == 1) {
                r.u(1); r.ue(); r.ue(); r.ue(); r.ue()
                reorder = r.ue()
            }
        }
        return Info(base.profileIdc, base.levelIdc, base.maxNumRefFrames, reorder)
    }

    private fun parseUpToVui(r: BitReader): Info {
        val profile = r.u(8)
        r.u(8) // constraint flags
        val level = r.u(8)
        r.ue() // seq_parameter_set_id
        if (profile in HIGH_PROFILES) {
            val chroma = r.ue()
            if (chroma == 3) r.u(1)
            r.ue(); r.ue() // bit depths
            r.u(1) // qpprime_y_zero_transform_bypass
            if (r.u(1) == 1) {
                val lists = if (chroma != 3) 8 else 12
                for (i in 0 until lists) if (r.u(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) {
            0 -> r.ue()
            1 -> {
                r.u(1); r.se(); r.se()
                val n = r.ue()
                repeat(n) { r.se() }
            }
        }
        val maxRef = r.ue()
        r.u(1) // gaps_in_frame_num_allowed
        r.ue(); r.ue() // width / height in mbs
        if (r.u(1) == 0) r.u(1) // frame_mbs_only / mb_adaptive_frame_field
        r.u(1) // direct_8x8_inference
        if (r.u(1) == 1) repeat(4) { r.ue() } // cropping
        return Info(profile, level, maxRef, null)
    }

    /** Called after vui_parameters_present_flag == 1; returns the bit position of bitstream_restriction_flag. */
    private fun skipVuiUntilRestriction(r: BitReader): Int {
        if (r.u(1) == 1) { if (r.u(8) == 255) { r.u(16); r.u(16) } }
        if (r.u(1) == 1) r.u(1)
        if (r.u(1) == 1) { r.u(3); r.u(1); if (r.u(1) == 1) { r.u(8); r.u(8); r.u(8) } }
        if (r.u(1) == 1) { r.ue(); r.ue() }
        if (r.u(1) == 1) { r.u(32); r.u(32); r.u(1) }
        val nal = r.u(1) == 1
        if (nal) skipHrd(r)
        val vcl = r.u(1) == 1
        if (vcl) skipHrd(r)
        if (nal || vcl) r.u(1)
        r.u(1) // pic_struct_present_flag
        return r.pos
    }

    private fun skipHrd(r: BitReader) {
        val cpbCnt = r.ue() + 1
        r.u(4); r.u(4)
        repeat(cpbCnt) { r.ue(); r.ue(); r.u(1) }
        r.u(5); r.u(5); r.u(5); r.u(5)
    }

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) next = (last + r.se() + 256) % 256
            last = if (next == 0) last else next
        }
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** Removes emulation prevention bytes (00 00 03 → 00 00) starting at [from]. */
    fun unescape(b: ByteArray, from: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var zeros = 0
        for (i in from until b.size) {
            val v = b[i].toInt() and 0xff
            if (zeros >= 2 && v == 3) {
                zeros = 0
                continue
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    fun escape(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size + 8)
        var zeros = 0
        for (x in b) {
            val v = x.toInt() and 0xff
            if (zeros >= 2 && v <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    class BitReader(private val d: ByteArray) {
        var pos = 0
            private set

        fun u(n: Int): Int {
            var v = 0L
            repeat(n) {
                val byte = pos ushr 3
                if (byte >= d.size) throw IllegalStateException("SPS truncated")
                v = (v shl 1) or ((d[byte].toInt() ushr (7 - (pos and 7))) and 1).toLong()
                pos++
            }
            return v.toInt()
        }

        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) if (++zeros > 31) throw IllegalStateException("bad exp-golomb")
            return ((1L shl zeros) - 1 + if (zeros > 0) u(zeros).toLong() and 0xffffffffL else 0L).toInt()
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }

    class BitWriter {
        private val out = java.io.ByteArrayOutputStream()
        private var cur = 0
        private var bits = 0

        fun u(v: Int, n: Int) {
            for (i in n - 1 downTo 0) bit((v ushr i) and 1)
        }

        fun ue(v: Int) {
            val x = v + 1
            val len = 32 - Integer.numberOfLeadingZeros(x)
            repeat(len - 1) { bit(0) }
            u(x, len)
        }

        fun se(v: Int) = ue(if (v > 0) 2 * v - 1 else -2 * v)

        fun copyBits(src: ByteArray, from: Int, to: Int) {
            for (p in from until to) bit((src[p ushr 3].toInt() ushr (7 - (p and 7))) and 1)
        }

        fun alignZero() {
            while (bits != 0) bit(0)
        }

        private fun bit(b: Int) {
            cur = (cur shl 1) or b
            if (++bits == 8) {
                out.write(cur)
                cur = 0
                bits = 0
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
