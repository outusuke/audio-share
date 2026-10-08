package io.github.mkckr0.audio_share_app

import io.github.mkckr0.audio_share_app.service.LossConcealer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LossConcealerTest {

    private val channels = 2
    private val frames = 960
    private val bytesPerFrame = channels * 2
    private val frameBytes = frames * bytesPerFrame

    private fun constantFrame(value: Int): ByteBuffer {
        val b = ByteBuffer.allocate(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * channels) { b.putShort(value.toShort()) }
        b.flip()
        return b
    }

    private fun sampleAt(out: ByteArray, frame: Int, channel: Int): Int {
        val i = (frame * channels + channel) * 2
        return ((out[i + 1].toInt() shl 8) or (out[i].toInt() and 0xff)).toShort().toInt()
    }

    @Test
    fun nothingToConceal_beforeFirstFrame() {
        val c = LossConcealer(frameBytes, bytesPerFrame)
        assertFalse(c.fill(1, ByteArray(frameBytes)))
    }

    @Test
    fun firstLostPacket_fadesFromFullScale() {
        val c = LossConcealer(frameBytes, bytesPerFrame)
        c.remember(constantFrame(-8000), frameBytes)
        val out = ByteArray(frameBytes)
        assertTrue(c.fill(1, out))
        assertEquals(-8000, sampleAt(out, 0, 0))
        assertEquals(-8000, sampleAt(out, 0, 1))
        assertEquals(-6000, sampleAt(out, frames - 1, 0), 10.0)
    }

    @Test
    fun laterLostPackets_continueTheFade() {
        val c = LossConcealer(frameBytes, bytesPerFrame)
        c.remember(constantFrame(8000), frameBytes)
        val out = ByteArray(frameBytes)
        c.fill(2, out)
        assertEquals(6000, sampleAt(out, 0, 0), 10.0)
        c.fill(4, out)
        assertEquals(0, sampleAt(out, frames - 1, 0), 10.0)
    }

    @Test
    fun smallChunks_buildUpAFrame() {
        val c = LossConcealer(frameBytes, bytesPerFrame)
        val half = ByteBuffer.allocate(frameBytes / 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * channels / 2) { half.putShort(1000) }
        half.flip()
        c.remember(half.duplicate(), half.remaining())
        assertFalse(c.fill(1, ByteArray(frameBytes)))
        c.remember(half.duplicate(), half.remaining())
        assertTrue(c.fill(1, ByteArray(frameBytes)))
    }

    @Test
    fun remember_doesNotConsumeTheBuffer() {
        val c = LossConcealer(frameBytes, bytesPerFrame)
        val b = constantFrame(5)
        c.remember(b, frameBytes)
        assertEquals(frameBytes, b.remaining())
    }
}
