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
    private var lastHead = -1L
    private var lastHeadMoveNs = 0L
    private var lastDebugLogNs = 0L

    /** Automatic gain for sources without their own volume control (NShare senders). */
    @Volatile var autoGain = false
    private var levelPeak = 0
    private var envelope = 0.0
    private var agcGain = 1.0

    private fun applyLevel(pcm: ShortArray) {
        var peak = 0
        for (v in pcm) {
            val a = if (v < 0) -v.toInt() else v.toInt()
            if (a > peak) peak = a
        }
        if (peak > levelPeak) levelPeak = peak
        if (!autoGain) return
        // Envelope: instant attack, ~10 s release. Gain brings the envelope towards -3 dBFS, at most +12 dB.
        envelope = maxOf(peak.toDouble(), envelope * 0.9990)
        val desired = if (envelope < 300) 1.0 else (23_000.0 / envelope).coerceIn(1.0, 4.0)
        agcGain += (desired - agcGain) * (if (desired < agcGain) 0.5 else 0.01)
        if (agcGain <= 1.01) return
        for (i in pcm.indices) {
            val x = pcm[i] * agcGain
            // Soft knee above ~-3 dBFS instead of hard clipping.
            val y = if (x > 23_000) 23_000 + (x - 23_000) / (1 + (x - 23_000) / 9_767) else if (x < -23_000) -23_000 + (x + 23_000) / (1 - (x + 23_000) / 9_767) else x
            pcm[i] = y.toInt().coerceIn(-32768, 32767).toShort()
        }
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
        applyLevel(pcm)
        val frames = pcm.size / channels
        if (frames == 0 || !timeline.isReady) return
        val now = System.nanoTime()
        val target = timeline.audioPresentationNs(remoteNs)
        val expected = predictNextFrameTime(now)
        val errorNs = expected - target
        stats.audioSyncErrorMs = errorNs / 1e6
        val frameNs = 1_000_000_000L / sampleRate

        if (errorNs <= DROP_THRESHOLD_NS) consecutiveLate = 0
        when {
            errorNs > DROP_THRESHOLD_NS && !timeline.atMaxLatency -> {
                // Audio would play too late: skip this chunk; the timeline grows latency if this persists.
                stats.audioDropped.incrementAndGet()
                if (++consecutiveLate >= 3) {
                    // The output pipeline is slower than the timeline allows: make room for it.
                    timeline.requireLatencyIncrease(errorNs + 20 * Timeline.MS)
                    consecutiveLate = 0
                }
                if (stats.audioDropped.get() % 50 == 1L) Log.d(TAG, "late by ${errorNs / 1_000_000} ms, dropping chunk")
                return
            }
            errorNs > DROP_THRESHOLD_NS -> {
                // Latency cannot grow any further: sound slightly out of sync beats no sound.
                needsAlignment = false
                write(pcm)
            }
            errorNs < -SILENCE_THRESHOLD_NS || needsAlignment && errorNs < -frameNs -> {
                val silenceFrames = (-errorNs / frameNs).toInt()
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
            needsAlignment = true
        }
        return now + bufferedNs + outputLatencyNs
    }

    private fun write(pcm: ShortArray) {
        var off = 0
        while (off < pcm.size && running) {
            val n = track.write(pcm, off, pcm.size - off, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            off += n
        }
        framesWritten += off / channels
    }

    private fun writeSilence(frames: Int) {
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

    private fun createTrack(): AudioTrack {
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
        private const val MAX_PENDING_INPUTS = 16
    }
}
