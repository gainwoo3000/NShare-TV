package com.nshare.receiver.player

import com.nshare.airplay.time.ClockSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    private val ms = Timeline.MS

    /** Clock with remote == local, so remote timestamps are local capture times. */
    private fun timeline(): Timeline = Timeline(ClockSync().apply { addNtpSample(0, 0) }, LatencyMode.BALANCED, 0)

    /** Feeds one video frame every 16 ms for [durationMs] with the given network transit. */
    private fun feedVideo(t: Timeline, startMs: Long, durationMs: Long, transitMs: Long, audio: Boolean = false, loud: Boolean = false): Long {
        var now = startMs
        while (now < startMs + durationMs) {
            t.onVideoArrival((now - transitMs) * ms, now * ms)
            if (audio) {
                t.onAudioArrival((now - 5) * ms, now * ms)
                t.onAudioLevel(silent = !loud, nowNs = now * ms)
            }
            now += 16
        }
        return now
    }

    @Test
    fun latencyRecoversFastWhenThereIsNoAudio() {
        val t = timeline()
        var now = feedVideo(t, 10_000, 6_000, transitMs = 300)   // Wi-Fi spike pushes latency up
        val high = t.latencyNs
        assertTrue("spike should raise latency, got ${high / ms}", high > 300 * ms)
        now = feedVideo(t, now, 6_000, transitMs = 5)              // spike leaves the 300-sample window
        now = feedVideo(t, now, 2_000, transitMs = 5)
        // 2 s of fast decay at 50 ms/s.
        assertTrue("expected fast recovery, got ${t.latencyNs / ms} ms", t.latencyNs < high - 150 * ms)
    }

    @Test
    fun latencyRecoversSlowlyWhileSoundPlays() {
        val t = timeline()
        var now = feedVideo(t, 10_000, 6_000, transitMs = 300, audio = true, loud = true)
        now = feedVideo(t, now, 6_000, transitMs = 5, audio = true, loud = true)
        val before = t.latencyNs
        feedVideo(t, now, 2_000, transitMs = 5, audio = true, loud = true)
        val dropped = (before - t.latencyNs) / ms
        // 5 ms/s: audio follows by resampling only, never faster than it can.
        assertTrue("expected ≤ ~10 ms over 2 s, got $dropped ms", dropped in 5..12)
    }

    @Test
    fun resampleLimitOutpacesSlowDecay() {
        // Correction capacity in ms/s must exceed the 5 ms/s decay for both AirPlay and NShare chunks.
        fun msPerSecond(frames: Int, rate: Int): Double {
            val perChunkMs = AudioRenderer.maxAdjustFrames(frames) * 1000.0 / rate
            val chunksPerSecond = rate.toDouble() / frames
            return perChunkMs * chunksPerSecond
        }
        assertTrue(msPerSecond(480, 44_100) > 5.0)
        assertTrue(msPerSecond(1024, 48_000) > 5.0)
        assertEquals(3, AudioRenderer.maxAdjustFrames(480))
        assertEquals(6, AudioRenderer.maxAdjustFrames(1024))
    }

    /** Video and loud audio where audio transit (150 ms) decides the latency, well above the mode minimum. */
    private fun feedAudioBound(t: Timeline, startMs: Long, durationMs: Long): Long {
        var now = startMs
        while (now < startMs + durationMs) {
            t.onVideoArrival((now - 5) * ms, now * ms)
            t.onAudioArrival((now - 150) * ms, now * ms)
            t.onAudioLevel(silent = false, nowNs = now * ms)
            now += 16
        }
        return now
    }

    @Test
    fun audioNeedKeepsTwoChunksQueued() {
        val t = timeline()
        val now = feedAudioBound(t, 10_000, 3_000)
        val without = t.latencyNs
        t.audioChunkNs = 21 * ms // AAC 1024 samples at 48 kHz
        feedAudioBound(t, now, 1_000)
        assertEquals("two chunks of room", 42L, (t.latencyNs - without) / ms)
    }

    @Test
    fun underrunsBuyRoomThatFadesSlowly() {
        val t = timeline()
        t.audioChunkNs = 10 * ms
        var now = feedAudioBound(t, 10_000, 3_000)
        val base = t.latencyNs
        t.onAudioUnderrun(now * ms)
        t.onAudioUnderrun(now * ms + 100 * ms) // within 500 ms: counted once
        now = feedAudioBound(t, now + 300, 300)
        val raised = t.latencyNs
        assertTrue("one step of ~15 ms, got ${(raised - base) / ms}", (raised - base) / ms in 14..15)
        feedAudioBound(t, now, 10_000)
        // 0.5 ms/s: after 10 s most of the room is still there.
        assertTrue("cushion should fade slowly, got ${(t.latencyNs - base) / ms} ms", t.latencyNs - base in 8 * ms..12 * ms)
    }
}
