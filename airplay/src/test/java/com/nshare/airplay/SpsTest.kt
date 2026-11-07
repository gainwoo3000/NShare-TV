package com.nshare.airplay

import com.nshare.airplay.util.H264Sps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpsTest {
    private fun buildSps(profile: Int, vui: Boolean, restriction: Boolean): ByteArray {
        val w = H264Sps.BitWriter()
        w.u(profile, 8); w.u(0, 8); w.u(51, 8); w.ue(0)
        if (profile == 100) { w.ue(1); w.ue(0); w.ue(0); w.u(0, 1); w.u(0, 1) }
        w.ue(0)             // log2_max_frame_num_minus4
        w.ue(2)             // poc type 2
        w.ue(1)             // max_num_ref_frames
        w.u(0, 1)
        w.ue(119); w.ue(67) // 1920x1088
        w.u(1, 1)           // frame_mbs_only
        w.u(1, 1)           // direct_8x8
        w.u(1, 1); w.ue(0); w.ue(0); w.ue(0); w.ue(4) // crop to 1080
        w.u(if (vui) 1 else 0, 1)
        if (vui) {
            w.u(1, 1); w.u(255, 8); w.u(1, 16); w.u(1, 16) // aspect ratio
            w.u(0, 1)
            w.u(1, 1); w.u(5, 3); w.u(0, 1); w.u(1, 1); w.u(1, 8); w.u(1, 8); w.u(1, 8)
            w.u(0, 1)
            w.u(1, 1); w.u(1, 32); w.u(120, 32); w.u(0, 1) // timing
            w.u(1, 1); w.ue(0); w.u(4, 4); w.u(6, 4); w.ue(1000); w.ue(2000); w.u(0, 1); repeat(4) { w.u(23, 5) } // nal hrd
            w.u(0, 1)
            w.u(0, 1)       // low_delay_hrd
            w.u(0, 1)       // pic_struct
            w.u(if (restriction) 1 else 0, 1)
            if (restriction) { w.u(1, 1); w.ue(2); w.ue(1); w.ue(16); w.ue(16); w.ue(4); w.ue(16) }
        }
        w.u(1, 1); w.alignZero()
        return byteArrayOf(0x67) + H264Sps.escape(w.toByteArray())
    }

    @Test
    fun addsRestrictionWhenVuiMissing() {
        val sps = buildSps(66, vui = false, restriction = false)
        assertNull(H264Sps.parse(sps).maxNumReorderFrames)
        val patched = H264Sps.patchLowLatency(sps)
        val info = H264Sps.parse(patched)
        assertEquals(0, info.maxNumReorderFrames)
        assertEquals(1, info.maxNumRefFrames)
        assertEquals(51, info.levelIdc)
    }

    @Test
    fun keepsVuiAndAppendsRestriction() {
        val sps = buildSps(100, vui = true, restriction = false)
        val patched = H264Sps.patchLowLatency(sps)
        assertEquals(0, H264Sps.parse(patched).maxNumReorderFrames)
        assertEquals(100, H264Sps.parse(patched).profileIdc)
        // Patching is idempotent.
        assertEquals(patched.toList(), H264Sps.patchLowLatency(patched).toList())
    }

    @Test
    fun replacesExistingRestriction() {
        val sps = buildSps(100, vui = true, restriction = true)
        assertEquals(4, H264Sps.parse(sps).maxNumReorderFrames)
        assertEquals(0, H264Sps.parse(H264Sps.patchLowLatency(sps)).maxNumReorderFrames)
    }

    @Test
    fun emulationPreventionRoundTrip() {
        val raw = byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 2, 5)
        val escaped = H264Sps.escape(raw)
        assertEquals(raw.toList(), H264Sps.unescape(byteArrayOf(0x67) + escaped, 1).toList())
    }
}
