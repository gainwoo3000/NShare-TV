package com.nshare.airplay.time

/**
 * Maps the sender's clock onto the local monotonic clock (System.nanoTime()).
 *
 * Primary source is the AirPlay NTP exchange (see [NtpClient]). Until the first NTP
 * sample arrives, a fallback derived from packet arrival times is used
 * (offset ≈ max(remote - arrival), i.e. assumes the fastest packet had zero transit).
 *
 * Small corrections are slewed so that playback never jumps; large ones are stepped.
 */
class ClockSync {
    private val lock = Any()

    private val samples = ArrayDeque<Sample>()
    private var ntpOffset = 0L
    private var hasNtpOffset = false

    private var arrivalOffset = Long.MIN_VALUE

    @Volatile
    private var currentOffset = 0L
    @Volatile
    var hasNtp: Boolean = false
        private set
    @Volatile
    var lastRoundTripNs: Long = 0
        private set

    private class Sample(val offset: Long, val delay: Long)

    /** offset = remote - local. */
    fun addNtpSample(offsetNs: Long, delayNs: Long) {
        synchronized(lock) {
            samples.addLast(Sample(offsetNs, delayNs))
            while (samples.size > WINDOW) samples.removeFirst()
            lastRoundTripNs = delayNs
            // The sample with the smallest round trip carries the least queueing error.
            val best = samples.minBy { it.delay }.offset
            if (!hasNtpOffset) {
                ntpOffset = best
                hasNtpOffset = true
            } else {
                val diff = best - ntpOffset
                ntpOffset += if (kotlin.math.abs(diff) > STEP_THRESHOLD_NS) diff
                else diff.coerceIn(-SLEW_PER_UPDATE_NS, SLEW_PER_UPDATE_NS)
            }
            currentOffset = ntpOffset
            hasNtp = true
        }
    }

    /** Fallback estimate from media arrivals; ignored once NTP is available. */
    fun observeArrival(remoteNs: Long, arrivalNs: Long) {
        if (hasNtp) return
        synchronized(lock) {
            val candidate = remoteNs - arrivalNs
            if (candidate > arrivalOffset) {
                arrivalOffset = candidate
                if (!hasNtp) currentOffset = candidate
            }
        }
    }

    val isReady: Boolean get() = hasNtp || arrivalOffset != Long.MIN_VALUE

    /** Sender time → local System.nanoTime() domain. */
    fun remoteToLocal(remoteNs: Long): Long = remoteNs - currentOffset

    fun localToRemote(localNs: Long): Long = localNs + currentOffset

    companion object {
        private const val WINDOW = 8
        private const val STEP_THRESHOLD_NS = 30_000_000L
        private const val SLEW_PER_UPDATE_NS = 500_000L
    }
}
