package io.github.mkckr0.audio_share_app.service

import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

// 16-bit little endian PCM only. MediaCodec has no way to ask for Opus PLC, so we repeat and fade the last frame.
class LossConcealer(private val frameBytes: Int, private val bytesPerFrame: Int) {

    private val last = ByteArray(frameBytes)
    private var filled = 0

    fun remember(pcm: ByteBuffer, size: Int) {
        val n = min(size, frameBytes)
        if (n < frameBytes) {
            System.arraycopy(last, n, last, 0, frameBytes - n)
        }
        val src = pcm.duplicate()
        src.position(src.position() + size - n)
        src.get(last, frameBytes - n, n)
        filled = min(frameBytes, filled + n)
    }

    // index is 1 for the first lost packet; each one fades further, reaching silence after FADE_PACKETS
    fun fill(index: Int, out: ByteArray): Boolean {
        if (filled < frameBytes || out.size < frameBytes) {
            return false
        }
        val frames = frameBytes / bytesPerFrame
        val channels = bytesPerFrame / 2
        val from = 1.0 - (index - 1).toDouble() / FADE_PACKETS
        val to = 1.0 - index.toDouble() / FADE_PACKETS
        for (f in 0 until frames) {
            val gain = from + (to - from) * f / frames
            for (c in 0 until channels) {
                val i = (f * channels + c) * 2
                val sample = (last[i + 1].toInt() shl 8) or (last[i].toInt() and 0xff)
                val v = (sample * gain).roundToInt()
                out[i] = v.toByte()
                out[i + 1] = (v shr 8).toByte()
            }
        }
        return true
    }

    companion object {
        private const val FADE_PACKETS = 4
    }
}
