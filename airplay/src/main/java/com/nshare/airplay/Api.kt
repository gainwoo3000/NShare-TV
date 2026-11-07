package com.nshare.airplay

import com.nshare.airplay.time.ClockSync

/** Static description of this receiver, advertised over mDNS and in GET /info. */
class ReceiverConfig(
    val name: String,
    /** Hardware-address style id "AA:BB:CC:DD:EE:FF". Must stay stable across restarts. */
    val deviceId: String,
    /** Stable UUID string ("pi"). */
    val pairingId: String,
    /** Ed25519 secret key (32 bytes). Must stay stable across restarts. */
    val identitySecret: ByteArray,
    val displayWidth: Int = 1920,
    val displayHeight: Int = 1080,
    val maxFps: Int = 60,
    val port: Int = 7000,
    /** Advertise H.265 support (features bit 42, "SupportsScreenMultiCodec") so senders can stream 4K. */
    val hevc: Boolean = false,
) {
    val features: Long = if (hevc) FEATURES or (1L shl 42) else FEATURES
    val featuresString: String
        get() = "0x%X,0x%X".format(features and 0xffffffffL, features ushr 32)

    companion object {
        /** AppleTV3,2 legacy feature set with "supports legacy pairing" (bit 27). */
        const val FEATURES = 0x5A7FFEE6L
        const val MODEL = "AppleTV3,2"
        const val SOURCE_VERSION = "220.68"
    }
}

enum class VideoCodec(val mime: String) {
    H264("video/avc"),
    H265("video/hevc"),
}

class VideoConfig(
    val codec: VideoCodec,
    /** Annex-B parameter sets (VPS/SPS/PPS with start codes). */
    val parameterSets: ByteArray,
    val width: Int,
    val height: Int,
)

class VideoFrame(
    /** Annex-B access unit. For key frames the parameter sets are prepended. */
    val data: ByteArray,
    /** Capture time on the sender clock, ns. */
    val remoteNs: Long,
    /** Local System.nanoTime() when the frame was fully received. */
    val arrivalNs: Long,
    val keyFrame: Boolean,
)

class AudioFormat(
    /** AirPlay compression type: 2 = ALAC, 4 = AAC-LC, 8 = AAC-ELD. */
    val compressionType: Int,
    val samplesPerFrame: Int,
    val sampleRate: Int = 44100,
    val channels: Int = 2,
    /** AAC AudioSpecificConfig when known (NShare senders); AirPlay formats are implied by [compressionType]. */
    val codecSpecificData: ByteArray? = null,
)

class AudioPacket(
    val data: ByteArray,
    /** Sender clock time of the first sample, ns. */
    val remoteNs: Long,
    val arrivalNs: Long,
)

interface VideoSink {
    fun onVideoConfig(config: VideoConfig)
    fun onVideoFrame(frame: VideoFrame)
    /** Sender stopped sending video (screen locked / app backgrounded). */
    fun onVideoPaused(paused: Boolean) {}
    fun onVideoStopped()
}

interface AudioSink {
    fun onAudioStart(format: AudioFormat)
    fun onAudioPacket(packet: AudioPacket)
    fun onAudioFlush()
    fun onAudioStop()
    /** AirPlay volume in dB: -144 = mute, otherwise -30..0. */
    fun onVolume(db: Float)
}

class ClientInfo(
    val address: String,
    val name: String?,
    val model: String?,
    val deviceId: String?,
)

/** Media endpoints for one connected sender. Created by [ReceiverListener.onSessionStart]. */
interface SessionSinks {
    val video: VideoSink
    val audio: AudioSink
    fun onSessionEnd()
}

interface ReceiverListener {
    /** [disconnect] drops this sender; called when another sender takes over the screen. */
    fun onSessionStart(client: ClientInfo, clock: ClockSync, disconnect: () -> Unit): SessionSinks
    fun onSessionEnd() {}
}
