package io.github.mkckr0.audio_share_app.service

import kotlin.math.abs

class JitterEstimator {

    @Volatile
    var jitterMs = 0.0
        private set

    private var firstArrivalNs = -1L
    private var mediaMs = 0.0
    private var lastTransit = 0.0
    private var primed = false

    // same smoothing as RFC 3550, just on audio time instead of RTP timestamps
    fun onPacket(arrivalNs: Long, durationMs: Double) {
        if (firstArrivalNs < 0) {
            firstArrivalNs = arrivalNs
        }
        val transit = (arrivalNs - firstArrivalNs) / 1e6 - mediaMs
        mediaMs += durationMs

        val delta = abs(transit - lastTransit)
        lastTransit = transit
        if (!primed) {
            primed = true
            return
        }
        // the server stops sending on silence, that gap isn't jitter
        if (delta > IDLE_GAP_MS) {
            return
        }
        jitterMs += (delta - jitterMs) / 16.0
    }

    companion object {
        private const val IDLE_GAP_MS = 1000.0
    }
}
