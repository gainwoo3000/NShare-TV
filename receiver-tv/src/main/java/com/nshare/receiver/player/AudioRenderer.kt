package com.nshare.receiver.player

import android.media.AudioAttributes
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.util.Log
import com.nshare.airplay.AudioFormat
import com.nshare.airplay.AudioPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow
import android.media.AudioFormat as AndroidAudioFormat

/**
 * Decodes AirPlay audio and plays it on a timeline shared with video.
 *
 * Before writing each decoded chunk we predict when its first sample will reach the output
 * (AudioTrack.getTimestamp) and compare with the slot the timeline assigns it:
 *  - small errors are absorbed by resampling the chunk by a few samples (inaudible),
 *  - large errors insert silence (audio early) or drop the chunk (audio late).
 */
class AudioRenderer(private val timeline: Timeline, private val stats: Stats) {
    private val queue = LinkedBlockingQueue<AudioPacket>(1024)
    @Volatile private var running = false
    @Volatile private var flushRequested = false
    @Volatile private var gain = 1f
    private var thread: Thread? = null

    fun start(format: AudioFormat) {
        stop()
        running = true
        queue.clear()
        thread = Thread({ run(format) }, "nshare-audio").apply {
            isDaemon = true
            start()
        }
    }

    fun submit(packet: AudioPacket) {
        stats.audioPacketsIn.incrementAndGet()
        if (!queue.offer(packet)) {
            queue.poll()
            queue.offer(packet)
            stats.audioDropped.incrementAndGet()
        }
    }

    fun flush() {
        queue.clear()
        flushRequested = true
    }

    fun setVolume(db: Float) {
        gain = if (db <= -30f) 0f else 10f.pow(db / 20f).coerceIn(0f, 1f)
    }

    fun stop() {
        running = false
        thread?.let {
            it.interrupt()
            try { it.join(500) } catch (_: InterruptedException) {}
        }
        thread = null
        queue.clear()
    }

    // ------------------------------------------------------------------ render thread

    private lateinit var track: AudioTrack
    private var sampleRate = 44100
    private var channels = 2
    private var framesWritten = 0L
    private var needsAlignment = true
    private val timestamp = AudioTimestamp()
    private var tsFrame = 0L
    private var tsNano = 0L
    private var tsValid = false
    private var tsQueriedAt = 0L
    private var outputLatencyNs = 40_000_000L
    private var appliedGain = -1f
    private val inputTimes = ArrayDeque<Long>()
    private var pendingPacket: AudioPacket? = null
    private var outputsLogged = 0
    private var consecutiveLate = 0
    private var lateDropsInRow = 0
    /** Error when the current run of late drops started, to tell whether dropping helps. */
    private var lateRunStartErrorNs = 0L
    /**
     * Dropping did not reduce the error at maximum latency (e.g. the output path is slower than
     * the mode allows): play every chunk late, shortened as much as allowed, instead of 1 in 9.
     */
    private var playingLate = false
    /** Audio queued in the track, from the last [predictNextFrameTime]. */
    private var queuedNs = 0L
    private var lastUnderrunCount = -1
    /** The next chunk starts after silence or a gap: ramp it in so the restart does not click. */
    private var fadeInNext = true
    private var lastHead = -1L
    private var lastHeadMoveNs = 0L
    private var lastDebugLogNs = 0L

    /** Automatic gain for sources without their own volume control (NShare senders). */
    @Volatile var autoGain = false
    private var levelPeak = 0
    private var envelope = 0.0
    private var agcGain = 1.0

    /** Meters the chunk (returns its peak before gain) and applies the automatic gain. */
    private fun applyLevel(pcm: ShortArray): Int {
        var peak = 0
        for (v in pcm) {
            val a = if (v < 0) -v.toInt() else v.toInt()
            if (a > peak) peak = a
        }
        if (peak > levelPeak) levelPeak = peak
        if (!autoGain) return peak
        // Envelope: instant attack, ~10 s release. Gain brings the envelope towards -3 dBFS, at most +12 dB.
        envelope = maxOf(peak.toDouble(), envelope * 0.9990)
        val desired = if (envelope < 300) 1.0 else (23_000.0 / envelope).coerceIn(1.0, 4.0)
        agcGain += (desired - agcGain) * (if (desired < agcGain) 0.5 else 0.01)
        if (agcGain <= 1.01) return peak
        for (i in pcm.indices) {
            val x = pcm[i] * agcGain
            // Soft knee above ~-3 dBFS instead of hard clipping.
            val y = if (x > 23_000) 23_000 + (x - 23_000) / (1 + (x - 23_000) / 9_767) else if (x < -23_000) -23_000 + (x + 23_000) / (1 - (x + 23_000) / 9_767) else x
            pcm[i] = y.toInt().coerceIn(-32768, 32767).toShort()
        }
        return peak
    }

    private fun dbfs(peak: Int): String = if (peak <= 0) "-inf" else "%.1f".format(20 * kotlin.math.log10(peak / 32768.0))

    private fun bufferedMs(frames: Long) = frames * 1000 / sampleRate

    private fun onDecoderFormat(f: MediaFormat) {
        val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        Log.i(TAG, "decoder output format: $rate Hz, $ch ch")
        if (rate != sampleRate || ch != channels) {
            sampleRate = rate
            channels = ch
            track.release()
            track = createTrack()
            lastUnderrunCount = -1
            track.play()
            framesWritten = 0
            tsValid = false
            needsAlignment = true
        }
    }

    private fun run(format: AudioFormat) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        sampleRate = format.sampleRate
        channels = format.channels
        val codec = try {
            createDecoder(format)
        } catch (e: Exception) {
            Log.e(TAG, "no decoder for audio ct=${format.compressionType}", e)
            null
        }
        if (codec == null) {
            // Keep draining so the receiver does not back up.
            while (running) queue.poll(100, TimeUnit.MILLISECONDS)
            return
        }
        track = createTrack()
        lastUnderrunCount = -1
        track.play()
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                if (flushRequested) {
                    flushRequested = false
                    codec.flush()
                    track.pause()
                    track.flush()
                    track.play()
                    framesWritten = 0
                    inputTimes.clear()
                    pendingPacket = null
                    tsValid = false
                    needsAlignment = true
                }
                if (appliedGain != gain) {
                    appliedGain = gain
                    track.setVolume(appliedGain)
                }
                // A packet the decoder had no room for is retried first instead of being dropped.
                val packet = pendingPacket ?: queue.poll(20, TimeUnit.MILLISECONDS)
                pendingPacket = null
                if (packet != null) {
                    val index = codec.dequeueInputBuffer(20_000)
                    if (index >= 0) {
                        val buf = codec.getInputBuffer(index)!!
                        buf.clear()
                        buf.put(packet.data)
                        codec.queueInputBuffer(index, 0, packet.data.size, packet.remoteNs / 1000, 0)
                        // Decoder output timestamps are unreliable for AAC-ELD (c2.android.aac assumes
                        // 1024-sample frames), so outputs are matched to inputs in order instead.
                        inputTimes.addLast(packet.remoteNs)
                        while (inputTimes.size > MAX_PENDING_INPUTS) inputTimes.removeFirst()
                    } else {
                        pendingPacket = packet
                    }
                }
                while (true) {
                    val out = codec.dequeueOutputBuffer(info, 0)
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        onDecoderFormat(codec.outputFormat)
                        continue
                    }
                    if (out < 0) break
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(out)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val pcm = ShortArray(info.size / 2)
                        buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
                        codec.releaseOutputBuffer(out, false)
                        val remoteNs = inputTimes.removeFirstOrNull() ?: continue
                        if (outputsLogged < 3) {
                            outputsLogged++
                            Log.i(TAG, "decoded ${pcm.size / channels} frames, pts=${info.presentationTimeUs} input=${remoteNs / 1000}")
                        }
                        writeScheduled(pcm, remoteNs)
                    } else {
                        codec.releaseOutputBuffer(out, false)
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.e(TAG, "audio render loop failed", e)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    private fun writeScheduled(pcm: ShortArray, remoteNs: Long) {
        val silent = applyLevel(pcm) < SILENT_PEAK
        val frames = pcm.size / channels
        if (frames == 0 || !timeline.isReady) return
        val now = System.nanoTime()
        timeline.onAudioLevel(silent, now)
        val target = timeline.audioPresentationNs(remoteNs)
        val expected = predictNextFrameTime(now)
        val errorNs = expected - target
        stats.audioSyncErrorMs = errorNs / 1e6
        val frameNs = 1_000_000_000L / sampleRate

        val chunkNs = frames * 1_000_000_000L / sampleRate
        timeline.audioChunkNs = chunkNs
        // Skipping or dropping while the track holds little audio makes it run dry: an audible click.
        val canDrop = queuedNs > 2 * chunkNs + MIN_QUEUE_MARGIN_NS
        if (errorNs > RESYNC_NS && !playingLate) {
            // Far behind (timestamps jumped, e.g. the sender restarted its audio stream): no amount of
            // dropping fixes that. Clear any backlog and play as audio arrives until timestamps line up.
            Log.w(TAG, "audio ${errorNs / 1_000_000} ms late; playing as it arrives")
            playingLate = true
            stats.audioPlayingLate = true
            if (queuedNs > FREE_RUN_MAX_QUEUE_NS) resetTrack()
        }
        if (errorNs <= DROP_THRESHOLD_NS) {
            consecutiveLate = 0
            lateDropsInRow = 0
            if (playingLate) {
                playingLate = false
                stats.audioPlayingLate = false
                Log.i(TAG, "audio back on time")
            }
        }
        when {
            silent && !needsAlignment && errorNs > chunkNs && canDrop -> {
                // Behind the timeline (latency is shrinking or a spike just passed): skipping silence
                // catches up instantly and inaudibly. Stops as soon as audio is back on its slot.
                stats.audioSilentDropped.incrementAndGet()
                return
            }
            errorNs > DROP_THRESHOLD_NS && playingLate -> {
                // Keep the sound continuous with a short queue: drop only when audio piles up.
                needsAlignment = false
                if (queuedNs > FREE_RUN_MAX_QUEUE_NS) {
                    stats.audioDropped.incrementAndGet()
                    return
                }
                write(pcm)
            }
            errorNs > DROP_THRESHOLD_NS && lateDropsInRow < MAX_LATE_DROPS_IN_ROW && canDrop -> {
                // Audio would play too late: skip this chunk so the backlog drains and lip sync returns.
                stats.audioDropped.incrementAndGet()
                if (lateDropsInRow == 0) lateRunStartErrorNs = errorNs
                lateDropsInRow++
                if (!timeline.atMaxLatency && ++consecutiveLate >= 3) {
                    // The output pipeline is slower than the timeline allows: make room for it.
                    timeline.requireLatencyIncrease(errorNs + 20 * Timeline.MS)
                    consecutiveLate = 0
                }
                if (stats.audioDropped.get() % 50 == 1L) Log.d(TAG, "late by ${errorNs / 1_000_000} ms, dropping chunk")
                return
            }
            errorNs > DROP_THRESHOLD_NS -> {
                // Still late after a run of drops: play one chunk so sound never disappears entirely.
                if (lateDropsInRow >= MAX_LATE_DROPS_IN_ROW && timeline.atMaxLatency && errorNs > lateRunStartErrorNs - LATE_PROGRESS_NS) {
                    // Latency cannot grow any more and dropping made no progress: stop chopping the sound.
                    playingLate = true
                    stats.audioPlayingLate = true
                    Log.w(TAG, "audio ${errorNs / 1_000_000} ms late at max latency; playing late instead of dropping")
                }
                lateDropsInRow = 0
                needsAlignment = false
                write(pcm)
            }
            errorNs < -MAX_EARLY_NS -> {
                // Due more than a second from now: timestamps not lined up yet. Filling that much
                // silence would push all following audio late, so skip the chunk instead.
                stats.audioDropped.incrementAndGet()
                return
            }
            errorNs < -SILENCE_THRESHOLD_NS || needsAlignment && errorNs < -frameNs -> {
                val silenceFrames = (minOf(-errorNs, MAX_SILENCE_NS) / frameNs).toInt()
                writeSilence(silenceFrames)
                stats.audioSilenceMs.addAndGet(silenceFrames * 1000L / sampleRate)
                needsAlignment = false
                write(pcm)
            }
            abs(errorNs) > RESAMPLE_DEADBAND_NS -> {
                // Positive error = behind → shorten the chunk; negative = ahead → stretch it.
                val maxAdjust = maxAdjustFrames(frames)
                val adjust = (errorNs / frameNs).toInt().coerceIn(-maxAdjust, maxAdjust)
                needsAlignment = false
                write(resample(pcm, frames, frames - adjust))
            }
            else -> {
                needsAlignment = false
                write(pcm)
            }
        }
    }

    /** Local time at which the next frame written to the track will be heard. */
    private fun predictNextFrameTime(now: Long): Long {
        if (now - tsQueriedAt > TIMESTAMP_REFRESH_NS) {
            tsQueriedAt = now
            if (track.getTimestamp(timestamp)) {
                tsFrame = timestamp.framePosition
                tsNano = timestamp.nanoTime
                tsValid = true
            }
        }
        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
        val buffered = (framesWritten - head).coerceAtLeast(0)
        if (head != lastHead) {
            lastHead = head
            lastHeadMoveNs = now
        } else if (buffered > 0 && now - lastHeadMoveNs > 500_000_000L) {
            Log.w(TAG, "playback head stuck at $head (written=$framesWritten state=${track.playState}), restarting track")
            track.play()
            lastHeadMoveNs = now
        }
        if (now - lastDebugLogNs > 5_000_000_000L) {
            lastDebugLogNs = now
            Log.i(
                TAG,
                "out: written=$framesWritten head=$head buffered=${bufferedMs(buffered)} ms tsValid=$tsValid " +
                    "tsAge=${if (tsValid) (now - tsNano) / 1_000_000 else -1} ms outLatency=${outputLatencyNs / 1_000_000} ms " +
                    "peak=${dbfs(levelPeak)} dBFS agc=${"%.1f".format(20 * kotlin.math.log10(agcGain))} dB",
            )
            levelPeak = 0
        }
        val bufferedNs = buffered * 1_000_000_000L / sampleRate
        queuedNs = bufferedNs
        // The platform's own count is exact (ours below only sees a completely empty queue).
        val underruns = track.underrunCount
        if (lastUnderrunCount in 0 until underruns) {
            timeline.onAudioUnderrun(now)
            fadeInNext = true
        }
        lastUnderrunCount = underruns
        if (tsValid && buffered > 0 && now - tsNano < 1_000_000_000L) {
            val t = tsNano + (framesWritten - tsFrame) * 1_000_000_000L / sampleRate
            val measured = t - now - bufferedNs
            if (measured in 0..500_000_000L) {
                outputLatencyNs = (outputLatencyNs * 7 + measured) / 8
                timeline.audioOutputLatencyNs = outputLatencyNs
            }
            return t
        }
        if (buffered == 0L && !needsAlignment) {
            stats.audioUnderruns.incrementAndGet()
            timeline.onAudioUnderrun(now)
            needsAlignment = true
            fadeInNext = true
        }
        return now + bufferedNs + outputLatencyNs
    }

    private fun write(pcm: ShortArray) {
        if (fadeInNext) {
            fadeInNext = false
            fadeIn(pcm)
        }
        var off = 0
        while (off < pcm.size && running) {
            val n = track.write(pcm, off, pcm.size - off, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            off += n
        }
        framesWritten += off / channels
    }

    /** Linear ramp over the first ~2 ms, so audio starting after silence or a gap does not click. */
    private fun fadeIn(pcm: ShortArray) {
        val rampFrames = minOf(sampleRate / 500, pcm.size / channels)
        if (rampFrames <= 1) return
        for (i in 0 until rampFrames) {
            for (ch in 0 until channels) {
                val k = i * channels + ch
                pcm[k] = (pcm[k] * i / rampFrames).toShort()
            }
        }
    }

    /** Drops everything queued in the track (a backlog that can never be caught up by dropping). */
    private fun resetTrack() {
        track.pause()
        track.flush()
        track.play()
        framesWritten = 0
        queuedNs = 0
        tsValid = false
        needsAlignment = true
        fadeInNext = true
    }

    private fun writeSilence(frames: Int) {
        fadeInNext = true
        var remaining = frames.coerceAtMost(sampleRate * 2) * channels
        val zeros = ShortArray(minOf(remaining, 4096))
        while (remaining > 0 && running) {
            val n = track.write(zeros, 0, minOf(remaining, zeros.size), AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            remaining -= n
            framesWritten += n / channels
        }
    }

    /** Linear-interpolation resample of an interleaved chunk from [inFrames] to [outFrames]. */
    private fun resample(pcm: ShortArray, inFrames: Int, outFrames: Int): ShortArray {
        if (outFrames == inFrames || outFrames <= 1) return pcm
        val out = ShortArray(outFrames * channels)
        val step = (inFrames - 1).toDouble() / (outFrames - 1)
        for (i in 0 until outFrames) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(inFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val frac = pos - i0
            for (ch in 0 until channels) {
                val a = pcm[i0 * channels + ch]
                val b = pcm[i1 * channels + ch]
                out[i * channels + ch] = (a + (b - a) * frac).toInt().toShort()
            }
        }
        return out
    }

    private fun createDecoder(format: AudioFormat): MediaCodec {
        val mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, format.sampleRate, format.channels)
        val csd = format.codecSpecificData
        when {
            csd != null -> {
                mf.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                mf.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            }
            format.compressionType == 8 -> {
                // AAC-ELD, 44.1 kHz, stereo, 480 samples per frame.
                mf.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
                mf.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)))
            }
            format.compressionType == 4 -> {
                mf.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                mf.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x12, 0x10)))
            }
            else -> throw IllegalArgumentException("unsupported compression type ${format.compressionType}")
        }
        mf.setInteger(MediaFormat.KEY_IS_ADTS, 0)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(mf, null, null, 0)
        codec.start()
        Log.i(TAG, "audio decoder ${codec.name} ct=${format.compressionType}")
        return codec
    }

    /** Prefers the low-latency output path (shorter mixer/HAL buffering); falls back if the device refuses. */
    private fun createTrack(): AudioTrack {
        val track = try {
            buildTrack(lowLatency = true)
        } catch (e: Exception) {
            Log.w(TAG, "low-latency AudioTrack refused (${e.message}), using the default path")
            buildTrack(lowLatency = false)
        }
        val lowLatency = track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        stats.audioPerfMode = if (lowLatency) "low-latency" else "default"
        Log.i(TAG, "AudioTrack perfMode=${stats.audioPerfMode}")
        return track
    }

    private fun buildTrack(lowLatency: Boolean): AudioTrack {
        val channelMask = if (channels == 1) AndroidAudioFormat.CHANNEL_OUT_MONO else AndroidAudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AndroidAudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBuf * 4, sampleRate * channels * 2 / 5) // >= 200 ms
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .setPerformanceMode(if (lowLatency) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY else AudioTrack.PERFORMANCE_MODE_NONE)
            .build()
            .also { t ->
                // Some TV outputs only start once the whole buffer is full; we schedule by timestamps
                // and need playback to begin as soon as data (or alignment silence) is there.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        t.setStartThresholdInFrames(sampleRate / 100)
                    } catch (e: Exception) {
                        Log.w(TAG, "setStartThresholdInFrames failed: ${e.message}")
                    }
                }
                val threshold = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) t.startThresholdInFrames else -1
                Log.i(TAG, "AudioTrack minBuf=$minBuf bytes buffer=${t.bufferSizeInFrames} frames startThreshold=$threshold")
            }
    }

    companion object {
        private const val TAG = "NShare.AudioOut"
        private const val DROP_THRESHOLD_NS = 80_000_000L
        private const val SILENCE_THRESHOLD_NS = 80_000_000L
        private const val RESAMPLE_DEADBAND_NS = 2_000_000L
        /**
         * Resampling may stretch or shrink a chunk by ~0.6 % (at least 3 frames): ≈ 6 ms/s of correction for
         * both AirPlay AAC-ELD (480 frames) and NShare AAC-LC (1024 frames), faster than the timeline's 5 ms/s
         * latency decay so audio can always follow it without dropping sound.
         */
        fun maxAdjustFrames(frames: Int): Int = maxOf(3, frames * 6 / 1000)
        private const val TIMESTAMP_REFRESH_NS = 100_000_000L
        /** Peak below -60 dBFS counts as silence (whispers and quiet backgrounds are far above this). */
        const val SILENT_PEAK = 33
        private const val MAX_LATE_DROPS_IN_ROW = 8
        /** A run of drops must shrink the error by at least this much to count as progress. */
        private const val LATE_PROGRESS_NS = 20 * Timeline.MS
        /** Beyond this, lateness is a timestamp jump, not a backlog: play as audio arrives. */
        private const val RESYNC_NS = 1_000 * Timeline.MS
        /** Chunks due further ahead than this are skipped instead of preceded by silence. */
        private const val MAX_EARLY_NS = 1_000 * Timeline.MS
        /** At most this much silence is inserted at once to wait for a chunk's slot. */
        private const val MAX_SILENCE_NS = 300 * Timeline.MS
        /** Queue kept in the track while playing as audio arrives. */
        private const val FREE_RUN_MAX_QUEUE_NS = 120 * Timeline.MS
        /** Besides two chunks, audio that must stay queued before skipping or dropping is allowed. */
        private const val MIN_QUEUE_MARGIN_NS = 10 * Timeline.MS
        private const val MAX_PENDING_INPUTS = 16
    }
}
