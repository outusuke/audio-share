package io.github.mkckr0.audio_share_app

import io.github.mkckr0.audio_share_app.service.JitterEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterEstimatorTest {

    private val ms = 1_000_000L

    @Test
    fun steadyArrivals_haveNoJitter() {
        val e = JitterEstimator()
        for (i in 0 until 200) e.onPacket(i * 20 * ms, 20.0)
        assertEquals(0.0, e.jitterMs, 1e-6)
    }

    @Test
    fun alternatingDelay_converges() {
        val e = JitterEstimator()
        for (i in 0 until 400) {
            val delay = if (i % 2 == 0) 0L else 10L
            e.onPacket((i * 20L + delay) * ms, 20.0)
        }
        assertEquals(10.0, e.jitterMs, 1.0)
    }

    @Test
    fun idleGap_isIgnored() {
        val e = JitterEstimator()
        for (i in 0 until 50) e.onPacket(i * 20 * ms, 20.0)
        for (i in 0 until 50) e.onPacket((10_000 + i * 20L) * ms, 20.0)
        assertTrue(e.jitterMs < 1.0)
    }
}
