package com.nshare.receiver.player

import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import com.nshare.airplay.VideoCodec
import com.nshare.airplay.VideoConfig
import com.nshare.airplay.VideoFrame
import java.nio.ByteBuffer

/**
 * Hardware video decoding with timestamp-scheduled presentation.
 *
 * Frames wait in a jitter queue and enter the decoder shortly before their presentation time;
 * decoded frames are released with releaseOutputBuffer(index, presentationNs) so SurfaceFlinger
 * shows them on the right vsync. When no on-screen surface exists the decoder keeps running
 * into an offscreen ImageReader, so the stream (which has no way to request a new key frame)
 * survives the activity being hidden.
 */
class VideoRenderer(
    private val timeline: Timeline,
    private val stats: Stats,
    private val onVideoSize: (width: Int, height: Int) -> Unit,
    /** Asks the sender for a fresh key frame (NShare); null when the protocol can't (AirPlay). */
    @Volatile var requestKeyFrame: (() -> Unit)? = null,
) {
    private val thread = HandlerThread("nshare-video", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    // All fields below are only touched on [handler]'s thread.
    private var codec: MediaCodec? = null
    private var codecConfig: VideoConfig? = null
    private var adaptive = false
    private var displaySurface: Surface? = null
    private var offscreen: ImageReader? = null
    private var currentOutput: Surface? = null
    private val queue = ArrayDeque<VideoFrame>()
    private val freeInputs = ArrayDeque<Int>()
    private val inputTimes = HashMap<Long, Long>()
    private var lastPtsUs = Long.MIN_VALUE
    private var waitingForKeyFrame = true
    private var released = false

    /** Frames since the last key frame, replayed after a decoder re-creation. */
    private val gop = ArrayList<VideoFrame>()
    private var gopBytes = 0L
    /** The GOP outgrew [MAX_GOP_BYTES]; replaying the partial GOP would only show garbage. */
    private var gopTruncated = false
    private var lastKeyFrameRequestNs = 0L
    /** Frames queued with pts <= this are decoded but not shown (replay catch-up). */
    private var catchUpUntilPtsUs = Long.MIN_VALUE

    private val feedRunnable = Runnable { feed() }

    fun setSurface(surface: Surface?) = handler.post {
        displaySurface = surface?.takeIf { it.isValid }
        val c = codec ?: return@post
        switchOutput(c)
    }

    fun configure(config: VideoConfig) = handler.post {
        if (released) return@post
        stats.videoWidth = config.width
        stats.videoHeight = config.height
        val current = codecConfig
        val sameStream = current != null && current.codec == config.codec &&
            current.parameterSets.contentEquals(config.parameterSets)
        if (codec != null && (sameStream || (adaptive && current?.codec == config.codec))) {
            // Parameter sets are prepended to the next key frame, adaptive decoders take them in-band.
            codecConfig = config
            return@post
        }
        codecConfig = config
        recreateCodec(replayGop = false)
    }

    fun submit(frame: VideoFrame) = handler.post {
        if (released) return@post
        stats.videoFramesIn.incrementAndGet()
        stats.videoBytesIn.addAndGet(frame.data.size.toLong())
        if (frame.keyFrame) {
            gop.clear()
            gopBytes = 0
            gopTruncated = false
            waitingForKeyFrame = false
        }
        if (waitingForKeyFrame) {
            askForKeyFrame("waiting for a key frame")
            return@post
        }
        if (gopBytes < MAX_GOP_BYTES) {
            gop += frame
            gopBytes += frame.data.size
        } else {
            gopTruncated = true
        }
        queue.addLast(frame)
        feed()
    }

    /** Sender stopped the video stream; keep the decoder, wait for the next key frame. */
    fun reset() = handler.post {
        queue.clear()
        gop.clear()
        gopBytes = 0
        waitingForKeyFrame = true
        releaseCodec()
        codecConfig = null
    }

    fun release() {
        handler.post {
            released = true
            queue.clear()
            gop.clear()
            releaseCodec()
            offscreen?.close()
            offscreen = null
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ decoder lifecycle

    private fun recreateCodec(replayGop: Boolean) {
        releaseCodec()
        val config = codecConfig ?: return
        val output = outputSurfaceFor(config)
        val c = try {
            createCodec(config, output)
        } catch (e: Exception) {
            Log.e(TAG, "decoder creation failed", e)
            null
        } ?: return
        codec = c
        currentOutput = output
        lastPtsUs = Long.MIN_VALUE
        queue.clear()
        if (replayGop) askForKeyFrame("decoder re-created")
        if (replayGop && gop.isNotEmpty() && !gopTruncated) {
            stats.videoDecoderResets.incrementAndGet()
            queue.addAll(gop)
            catchUpUntilPtsUs = gop.last().remoteNs / 1000
            Log.i(TAG, "replaying ${gop.size} frames after decoder reset")
        } else {
            waitingForKeyFrame = true
        }
    }

    private fun askForKeyFrame(reason: String) {
        val request = requestKeyFrame ?: return
        val now = System.nanoTime()
        if (now - lastKeyFrameRequestNs < KEY_FRAME_REQUEST_INTERVAL_NS) return
        lastKeyFrameRequestNs = now
        Log.i(TAG, "requesting key frame: $reason")
        request()
    }

    private fun releaseCodec() {
        handler.removeCallbacks(feedRunnable)
        freeInputs.clear()
        inputTimes.clear()
        codec?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        codec = null
        currentOutput = null
    }

    private fun outputSurfaceFor(config: VideoConfig): Surface {
        displaySurface?.takeIf { it.isValid }?.let { return it }
        val w = config.width.coerceAtLeast(16)
        val h = config.height.coerceAtLeast(16)
        val existing = offscreen
        if (existing != null && existing.width == w && existing.height == h) return existing.surface
        existing?.close()
        val reader = try {
            ImageReader.newInstance(w, h, ImageFormat.PRIVATE, 4)
        } catch (e: Exception) {
            ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 4)
        }
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
        offscreen = reader
        return reader.surface
    }

    private fun switchOutput(c: MediaCodec) {
        val config = codecConfig ?: return
        val target = outputSurfaceFor(config)
        if (target === currentOutput) return
        try {
            c.setOutputSurface(target)
            currentOutput = target
            Log.i(TAG, "output surface switched (${if (target === displaySurface) "display" else "offscreen"})")
            askForKeyFrame("output surface changed")
        } catch (e: Exception) {
            Log.w(TAG, "setOutputSurface failed, recreating decoder: ${e.message}")
            recreateCodec(replayGop = true)
        }
    }

    private fun createCodec(config: VideoConfig, output: Surface): MediaCodec {
        val info = pickDecoder(config.codec.mime) ?: throw IllegalStateException("no decoder for ${config.codec}")
        val caps = info.getCapabilitiesForType(config.codec.mime)
        adaptive = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)
        // Many vendor decoders (e.g. MediaTek) honour KEY_LOW_LATENCY without advertising the feature.
        val lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        val format = MediaFormat.createVideoFormat(config.codec.mime, config.width, config.height)
        val nals = splitAnnexB(config.parameterSets)
        when (config.codec) {
            VideoCodec.H264 -> {
                nals.getOrNull(0)?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                nals.getOrNull(1)?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            }
            VideoCodec.H265 -> format.setByteBuffer("csd-0", ByteBuffer.wrap(config.parameterSets))
        }
        if (adaptive) {
            // Allow rotation (portrait senders) without re-creating the decoder when the size is supported.
            val maxDim = maxOf(config.width, config.height)
            val square = runCatching { caps.videoCapabilities.isSizeSupported(maxDim, maxDim) }.getOrDefault(false)
            format.setInteger(MediaFormat.KEY_MAX_WIDTH, if (square) maxDim else config.width)
            format.setInteger(MediaFormat.KEY_MAX_HEIGHT, if (square) maxDim else config.height)
        }
        // Key frames of a high-quality 4K stream can exceed the default input buffer size.
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(config.width * config.height, 2 * 1024 * 1024))
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        // Without a frame rate hint vendor decoders (MediaTek) clock for 30 fps, which caps output
        // at 30 fps and lets a 60 fps stream back up in the input queue.
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, 120)
        if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)

        val codec = MediaCodec.createByCodecName(info.name)
        codec.setCallback(callback, handler)
        try {
            codec.configure(format, output, null, 0)
        } catch (e: Exception) {
            // Some vendor decoders reject optional keys; retry with the bare minimum.
            Log.w(TAG, "configure failed with tuning keys, retrying: ${e.message}")
            codec.reset()
            codec.setCallback(callback, handler)
            format.removeKey(MediaFormat.KEY_PRIORITY)
            format.removeKey(MediaFormat.KEY_OPERATING_RATE)
            if (lowLatency) format.removeKey(MediaFormat.KEY_LOW_LATENCY)
            codec.configure(format, output, null, 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                output.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            } catch (_: Exception) {
            }
        }
        codec.start()
        try {
            val vc = caps.videoCapabilities
            Log.i(
                TAG,
                "decoder caps: fps@${config.width}x${config.height}=${vc.getSupportedFrameRatesFor(config.width, config.height)} " +
                    "achievable=${runCatching { vc.getAchievableFrameRatesFor(config.width, config.height) }.getOrNull()}",
            )
        } catch (_: Exception) {
        }
        stats.codecName = info.name + (if (lowLatency) " LL" else "") + (if (adaptive) " A" else "")
        Log.i(TAG, "decoder ${info.name} ${config.width}x${config.height} adaptive=$adaptive lowLatency=$lowLatency")
        return codec
    }

    private fun pickDecoder(mime: String): MediaCodecInfo? {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
        fun isSoftware(i: MediaCodecInfo): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) i.isSoftwareOnly
            else i.name.startsWith("OMX.google.") || i.name.startsWith("c2.android.")
        return all.firstOrNull { !isSoftware(it) } ?: all.firstOrNull()
    }

    // ------------------------------------------------------------------ feeding / output

    private fun feed() {
        val c = codec ?: return
        handler.removeCallbacks(feedRunnable)
        while (freeInputs.isNotEmpty() && queue.isNotEmpty()) {
            val frame = queue.first()
            val ptsUs = frame.remoteNs / 1000
            val now = System.nanoTime()
            val catchingUp = ptsUs <= catchUpUntilPtsUs
            if (!catchingUp) {
                val due = timeline.videoPresentationNs(frame.remoteNs) - timeline.decodeLeadNs()
                if (due > now) {
                    handler.postDelayed(feedRunnable, ((due - now) / 1_000_000L).coerceIn(1, 500))
                    return
                }
            }
            queue.removeFirst()
            val index = freeInputs.removeFirst()
            try {
                val buf = c.getInputBuffer(index) ?: continue
                if (buf.capacity() < frame.data.size) {
                    Log.w(TAG, "frame ${frame.data.size} bytes exceeds input buffer ${buf.capacity()}")
                    c.queueInputBuffer(index, 0, 0, ptsUs, 0)
                    continue
                }
                buf.clear()
                buf.put(frame.data)
                val pts = if (ptsUs <= lastPtsUs) lastPtsUs + 1 else ptsUs
                lastPtsUs = pts
                val flags = if (frame.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                c.queueInputBuffer(index, 0, frame.data.size, pts, flags)
                if (inputTimes.size < 256) inputTimes[pts] = now
            } catch (e: Exception) {
                Log.e(TAG, "queueInputBuffer failed", e)
                recreateCodec(replayGop = true)
                return
            }
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            if (mc !== codec) return
            freeInputs.addLast(index)
            feed()
        }

        override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (mc !== codec) return
            val now = System.nanoTime()
            inputTimes.remove(info.presentationTimeUs)?.let { timeline.onDecodeLatency(now - it) }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || info.size == 0 && info.flags != 0) {
                mc.releaseOutputBuffer(index, false)
                return
            }
            val target = timeline.videoPresentationNs(info.presentationTimeUs * 1000)
            try {
                if (info.presentationTimeUs < catchUpUntilPtsUs && target < now) {
                    mc.releaseOutputBuffer(index, false)
                    stats.videoSkipped.incrementAndGet()
                    return
                }
                val late = now - target
                if (late > LATE_THRESHOLD_NS) {
                    stats.videoLate.incrementAndGet()
                    stats.lastLateMs = late / 1e6
                }
                val renderAt = if (target - now > 1_000_000_000L) now else maxOf(target, now)
                mc.releaseOutputBuffer(index, renderAt)
                stats.videoRendered.incrementAndGet()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "releaseOutputBuffer failed: ${e.message}")
            }
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            if (mc !== codec) return
            Log.e(TAG, "decoder error (recoverable=${e.isRecoverable}, transient=${e.isTransient})", e)
            recreateCodec(replayGop = true)
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            if (mc !== codec) return
            var w = format.getInteger(MediaFormat.KEY_WIDTH)
            var h = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
                w = format.getInteger("crop-right") + 1 - format.getInteger("crop-left")
            }
            if (format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
                h = format.getInteger("crop-bottom") + 1 - format.getInteger("crop-top")
            }
            Log.i(TAG, "output format ${w}x$h")
            stats.videoWidth = w
            stats.videoHeight = h
            onVideoSize(w, h)
        }
    }

    companion object {
        private const val TAG = "NShare.Video"
        private const val MAX_GOP_BYTES = 48L * 1024 * 1024
        private const val KEY_FRAME_REQUEST_INTERVAL_NS = 1_000_000_000L
        private const val LATE_THRESHOLD_NS = 8_000_000L

        fun splitAnnexB(data: ByteArray): List<ByteArray> {
            val starts = ArrayList<Int>()
            var i = 0
            while (i + 3 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts += i
                    i += 4
                } else {
                    i++
                }
            }
            return starts.mapIndexed { idx, s ->
                val end = if (idx + 1 < starts.size) starts[idx + 1] else data.size
                data.copyOfRange(s, end)
            }
        }
    }
}
