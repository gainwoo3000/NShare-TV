package com.nshare.receiver.player

import com.nshare.airplay.AudioFormat
import com.nshare.airplay.AudioPacket
import com.nshare.airplay.AudioSink
import com.nshare.airplay.TrackInfo
import com.nshare.airplay.ClientInfo
import com.nshare.airplay.SessionSinks
import com.nshare.airplay.VideoConfig
import com.nshare.airplay.VideoFrame
import com.nshare.airplay.VideoSink
import com.nshare.airplay.time.ClockSync

/** Playback pipeline for one connected sender. */
class MirrorPlayer(
    val client: ClientInfo,
    val clock: ClockSync,
    mode: LatencyMode,
    avOffsetMs: Int,
    private val events: Events,
    requestKeyFrame: (() -> Unit)? = null,
) : SessionSinks {

    interface Events {
        fun onVideoSize(player: MirrorPlayer, width: Int, height: Int)
        fun onVideoActive(player: MirrorPlayer, active: Boolean)
        fun onEnded(player: MirrorPlayer)
        fun onTrackInfo(player: MirrorPlayer, info: TrackInfo) {}
        fun onArtwork(player: MirrorPlayer, image: ByteArray?) {}
        fun onProgress(player: MirrorPlayer, elapsedMs: Long, durationMs: Long) {}
    }

    val timeline = Timeline(clock, mode, avOffsetMs)
    val stats = Stats()
    private val videoRenderer = VideoRenderer(
        timeline, stats, { w, h -> events.onVideoSize(this, w, h) }, requestKeyFrame,
    )
    private val audioRenderer = AudioRenderer(timeline, stats)
    @Volatile private var ended = false

    private val statsLogger = Thread({
        try {
            while (!ended) {
                Thread.sleep(5000)
                val r = stats.rates()
                android.util.Log.i(
                    "NShare.Stats",
                    "%s perf=%s | in %.1f fps out %.1f fps %.1f Mbps late=%d skipped=%d | audio in=%d err=%+.1f ms dropped=%d silentSkipped=%d silence=%d ms underruns=%d | ntp=%s rtt=%.1f ms".format(
                        timeline.summary(), stats.audioPerfMode, r.inputFps, r.renderFps, r.mbps, stats.videoLate.get(), stats.videoSkipped.get(),
                        stats.audioPacketsIn.get(), stats.audioSyncErrorMs, stats.audioDropped.get(), stats.audioSilentDropped.get(), stats.audioSilenceMs.get(),
                        stats.audioUnderruns.get(), clock.hasNtp, clock.lastRoundTripNs / 1e6,
                    ),
                )
            }
        } catch (_: InterruptedException) {
        }
    }, "nshare-stats").apply {
        isDaemon = true
        start()
    }

    fun setSurface(surface: android.view.Surface?) = videoRenderer.setSurface(surface)

    /** A reconnected NShare session brings its own control channel. */
    fun setKeyFrameRequester(request: (() -> Unit)?) {
        videoRenderer.requestKeyFrame = request
    }

    override val video: VideoSink = object : VideoSink {
        override fun onVideoConfig(config: VideoConfig) {
            videoRenderer.configure(config)
            events.onVideoActive(this@MirrorPlayer, true)
        }

        override fun onVideoFrame(frame: VideoFrame) {
            timeline.onVideoArrival(frame.remoteNs, frame.arrivalNs)
            videoRenderer.submit(frame)
        }

        override fun onVideoPaused(paused: Boolean) = events.onVideoActive(this@MirrorPlayer, !paused)

        override fun onVideoStopped() {
            videoRenderer.reset()
            events.onVideoActive(this@MirrorPlayer, false)
        }
    }

    /** When the last audio packet arrived (elapsedRealtime); music is paused when packets stop. */
    @Volatile var lastAudioPacketMs = 0L
        private set
    /** When audio started flowing again after a gap. */
    @Volatile var audioFlowStartMs = 0L
        private set

    /**
     * Music is playing: packets keep arriving, and have done so for a moment. A short burst alone
     * (a sender pre-rolling the next track while paused) does not count.
     */
    fun audioFlowing(nowMs: Long): Boolean =
        nowMs - lastAudioPacketMs < AUDIO_GAP_MS && nowMs - audioFlowStartMs >= AUDIO_SUSTAIN_MS

    override val audio: AudioSink = object : AudioSink {
        override fun onAudioStart(format: AudioFormat) {
            // NShare senders have no volume channel, so level their (often quiet) capture automatically.
            audioRenderer.autoGain = format.codecSpecificData != null
            audioRenderer.start(format)
        }

        override fun onAudioPacket(packet: AudioPacket) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAudioPacketMs > AUDIO_GAP_MS) audioFlowStartMs = now
            lastAudioPacketMs = now
            timeline.onAudioArrival(packet.remoteNs, packet.arrivalNs)
            audioRenderer.submit(packet)
        }

        override fun onAudioFlush() = audioRenderer.flush()
        override fun onAudioStop() = audioRenderer.stop()
        override fun onVolume(db: Float) = audioRenderer.setVolume(db)
        override fun onTrackInfo(info: TrackInfo) = events.onTrackInfo(this@MirrorPlayer, info)
        override fun onArtwork(image: ByteArray?) = events.onArtwork(this@MirrorPlayer, image)
        override fun onProgress(elapsedMs: Long, durationMs: Long) = events.onProgress(this@MirrorPlayer, elapsedMs, durationMs)
    }

    override fun onSessionEnd() {
        if (ended) return
        ended = true
        statsLogger.interrupt()
        audioRenderer.stop()
        videoRenderer.release()
        events.onEnded(this)
    }

    private companion object {
        const val AUDIO_GAP_MS = 700L
        const val AUDIO_SUSTAIN_MS = 800L
    }
}
