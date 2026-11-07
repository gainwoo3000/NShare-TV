package com.nshare.receiver.player

import java.util.concurrent.atomic.AtomicLong

/** Counters shown in the on-screen diagnostics overlay. */
class Stats {
    val videoFramesIn = AtomicLong()
    val videoBytesIn = AtomicLong()
    val videoRendered = AtomicLong()
    val videoLate = AtomicLong()
    val videoSkipped = AtomicLong()
    val videoDecoderResets = AtomicLong()
    val audioPacketsIn = AtomicLong()
    val audioDropped = AtomicLong()
    val audioSilenceMs = AtomicLong()
    val audioUnderruns = AtomicLong()

    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0
    @Volatile var codecName = ""
    @Volatile var lastLateMs = 0.0
    /** Last measured audio scheduling error (positive = audio behind its slot). */
    @Volatile var audioSyncErrorMs = 0.0

    private var lastSnapshotNs = System.nanoTime()
    private var lastRendered = 0L
    private var lastBytes = 0L
    private var lastFramesIn = 0L

    class Rates(val renderFps: Double, val inputFps: Double, val mbps: Double)

    @Synchronized
    fun rates(): Rates {
        val now = System.nanoTime()
        val dt = (now - lastSnapshotNs).coerceAtLeast(1) / 1e9
        val rendered = videoRendered.get()
        val bytes = videoBytesIn.get()
        val framesIn = videoFramesIn.get()
        val r = Rates(
            renderFps = (rendered - lastRendered) / dt,
            inputFps = (framesIn - lastFramesIn) / dt,
            mbps = (bytes - lastBytes) * 8 / dt / 1e6,
        )
        lastSnapshotNs = now
        lastRendered = rendered
        lastBytes = bytes
        lastFramesIn = framesIn
        return r
    }
}
