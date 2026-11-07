package com.nshare.receiver.player

import com.nshare.airplay.time.ClockSync
import com.nshare.receiver.R

/**
 * Playout latency presets. Latency adapts within [minMs, maxMs] to the measured network
 * transit and decode times so that frames are (almost) never late, which is what makes
 * playback smooth; lower presets trade robustness against Wi-Fi hiccups for responsiveness.
 */
enum class LatencyMode(
    val labelRes: Int,
    val minMs: Int,
    val maxMs: Int,
    /** Fraction of recent frames that must arrive in time. */
    val percentile: Double,
    val marginMs: Int,
) {
    LOW(R.string.mode_low, 60, 400, 0.95, 10),
    BALANCED(R.string.mode_balanced, 110, 600, 0.99, 20),
    SMOOTH(R.string.mode_smooth, 220, 1000, 1.0, 40);

    fun next(): LatencyMode = entries[(ordinal + 1) % entries.size]
    fun previous(): LatencyMode = entries[(ordinal + entries.size - 1) % entries.size]
}

/**
 * Common presentation timeline for audio and video.
 *
 * Both streams carry capture timestamps on the sender clock. Each sample is presented at
 *   local(remote) + latency            (video)
 *   local(remote) + latency + avOffset (audio)
 * so lip sync holds by construction; only the shared latency adapts.
 */
class Timeline(private val clock: ClockSync, mode: LatencyMode, avOffsetMs: Int) {
    @Volatile var mode: LatencyMode = mode
        set(value) {
            field = value
            latencyNs = latencyNs.coerceIn(value.minMs * MS, value.maxMs * MS)
        }

    @Volatile var avOffsetNs: Long = avOffsetMs * MS

    @Volatile var latencyNs: Long = mode.minMs * MS
        private set

    /** Measured audio output pipeline latency (AudioTrack + HAL), set by the audio renderer. */
    @Volatile var audioOutputLatencyNs: Long = 0

    private val videoTransit = SampleWindow(300)
    private val audioTransit = SampleWindow(300)
    private val decodeLatency = SampleWindow(120)
    private var lastUpdateNs = 0L
    private var clockHadNtp = false

    val isReady: Boolean get() = clock.isReady

    fun videoPresentationNs(remoteNs: Long): Long = clock.remoteToLocal(remoteNs) + latencyNs

    fun audioPresentationNs(remoteNs: Long): Long = clock.remoteToLocal(remoteNs) + latencyNs + avOffsetNs

    fun onVideoArrival(remoteNs: Long, arrivalNs: Long) {
        checkClockSource()
        videoTransit.add(arrivalNs - clock.remoteToLocal(remoteNs))
        maybeUpdate(arrivalNs)
    }

    fun onAudioArrival(remoteNs: Long, arrivalNs: Long) {
        checkClockSource()
        audioTransit.add(arrivalNs - clock.remoteToLocal(remoteNs))
        maybeUpdate(arrivalNs)
    }

    fun onDecodeLatency(ns: Long) = decodeLatency.add(ns)

    val atMaxLatency: Boolean get() = latencyNs >= mode.maxMs * MS

    /** Called when a renderer cannot meet its deadlines by [deficitNs]. */
    @Synchronized
    fun requireLatencyIncrease(deficitNs: Long) {
        val m = mode
        latencyNs = (latencyNs + deficitNs.coerceAtLeast(0)).coerceIn(m.minMs * MS, m.maxMs * MS)
    }

    fun summary(): String = "latency=%d ms (%s) vTransit95=%.0f aTransit95=%.0f decode95=%.0f audioOut=%.0f ms".format(
        latencyNs / MS, mode.name, videoTransitP95Ms(), audioTransitP95Ms(), decodeP95Ms(), audioOutputLatencyNs / 1e6,
    )

    fun videoTransitP95Ms(): Double = (videoTransit.percentile(0.95) ?: 0) / 1e6
    fun audioTransitP95Ms(): Double = (audioTransit.percentile(0.95) ?: 0) / 1e6
    fun decodeP95Ms(): Double = (decodeLatency.percentile(0.95) ?: 0) / 1e6

    /** Time to queue a frame into the decoder ahead of its presentation. */
    fun decodeLeadNs(): Long = ((decodeLatency.percentile(0.95) ?: 30 * MS) + 15 * MS).coerceIn(25 * MS, 90 * MS)

    private fun checkClockSource() {
        // Transit statistics computed with the arrival-based fallback clock are meaningless
        // once NTP takes over, so start over.
        val ntp = clock.hasNtp
        if (ntp != clockHadNtp) {
            clockHadNtp = ntp
            videoTransit.clear()
            audioTransit.clear()
        }
    }

    @Synchronized
    private fun maybeUpdate(now: Long) {
        if (now - lastUpdateNs < UPDATE_INTERVAL_NS) return
        lastUpdateNs = now
        val m = mode
        val vsync = 17 * MS
        val videoNeed = videoTransit.percentile(m.percentile)?.let {
            it + (decodeLatency.percentile(0.95) ?: 20 * MS) + vsync + m.marginMs * MS
        }
        val audioNeed = audioTransit.percentile(m.percentile)?.let {
            it + audioOutputLatencyNs + AUDIO_DECODE_NS + m.marginMs * MS
        }
        val need = maxOf(videoNeed ?: 0, audioNeed ?: 0).coerceIn(m.minMs * MS, m.maxMs * MS)
        latencyNs = if (need > latencyNs) {
            need
        } else {
            maxOf(need, latencyNs - DECAY_PER_UPDATE_NS)
        }
    }

    companion object {
        const val MS = 1_000_000L
        private const val UPDATE_INTERVAL_NS = 250 * MS
        /** 1.25 ms per 250 ms = 5 ms/s: slow enough for audio to follow by resampling. */
        private const val DECAY_PER_UPDATE_NS = 1_250_000L
        private const val AUDIO_DECODE_NS = 12 * MS
    }
}

/** Fixed-size ring of recent samples with percentile queries. Thread-safe. */
class SampleWindow(private val capacity: Int) {
    private val data = LongArray(capacity)
    private var count = 0
    private var pos = 0

    @Synchronized
    fun add(v: Long) {
        data[pos] = v
        pos = (pos + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized
    fun clear() {
        count = 0
        pos = 0
    }

    @Synchronized
    fun percentile(p: Double): Long? {
        if (count < 8) return null
        val copy = data.copyOf(count)
        copy.sort()
        val idx = ((count - 1) * p).toInt().coerceIn(0, count - 1)
        return copy[idx]
    }
}
