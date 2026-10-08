package io.github.mkckr0.audio_share_app.service

import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class AudioPipeline(
    private val track: AudioTrack,
    private val decoder: OpusDecoder?,
    private val bytesPerFrame: Int,
    private val bytesPerSecond: Int,
    private val profile: LatencyProfile,
    private val onStats: (String) -> Unit,
) : Thread("AudioShare-Playback") {

    private val tag = AudioPipeline::class.simpleName

    private val packetMs =
        if (decoder != null) OPUS_PACKET_MS.toDouble() else PCM_DATAGRAM_BYTES * 1000.0 / bytesPerSecond
    private val capacity = max(MIN_QUEUE_PACKETS, ceil(profile.maxQueueMs / packetMs).toInt())
    private val queue = ArrayBlockingQueue<ByteBuffer>(capacity)

    private val overflowDrops = AtomicLong()
    private val resyncPending = AtomicBoolean()
    private val sequence = SequenceTracker()
    private val jitter = JitterEstimator()

    private val frameBytes = bytesPerSecond * OPUS_PACKET_MS / 1000
    private val concealer = if (decoder != null) LossConcealer(frameBytes, bytesPerFrame) else null
    private val concealBuffer = ByteArray(if (decoder != null) frameBytes else 0)

    @Volatile
    private var running = true

    @Volatile
    private var lastArrivalMs = 0L

    private val prefillFrames = maxOf(1, track.bufferSizeInFrames / 2)
    private var framesPrefilled = 0L
    private var started = false
    private var gateOpen = false
    private var gateSince = 0L

    private var floorMs = 0.0
    private var lastUnderruns = 0
    private var lastUnderrunAt = 0L
    private var lastDecayAt = 0L

    private var windowMin = Int.MAX_VALUE
    private var windowStart = 0L
    private var lastTickAt = 0L
    private var lastStatsAt = 0L
    private var concealed = 0L
    private var trimmed = 0L

    fun submit(packet: ByteBuffer) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val durationMs = if (decoder != null) packetMs else packet.remaining() * 1000.0 / bytesPerSecond
        jitter.onPacket(nowNs, durationMs)
        lastArrivalMs = nowNs / 1_000_000

        // hard cap only; the consumer trims steady backlog (drift) on its own
        while (!queue.offer(packet)) {
            if (queue.poll() != null) {
                overflowDrops.incrementAndGet()
                resyncPending.set(true)
            }
        }
    }

    fun shutdown() {
        running = false
        interrupt()
        join(1000)
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            while (running) {
                if (!started && !gateOpen && !prebuffered()) {
                    Thread.sleep(PREBUFFER_POLL_MS)
                } else {
                    val packet = queue.poll(100, TimeUnit.MILLISECONDS)
                    if (packet != null) {
                        windowMin = min(windowMin, queue.size)
                        handle(packet)
                    }
                }
                tick()
            }
        } catch (e: InterruptedException) {
        } catch (e: Exception) {
            Log.e(tag, e.stackTraceToString())
        } finally {
            decoder?.release()
        }
    }

    private fun handle(packet: ByteBuffer) {
        if (decoder == null) {
            write(packet, packet.remaining())
            return
        }

        if (packet.remaining() <= SEQ_BYTES) {
            return
        }
        if (resyncPending.getAndSet(false)) {
            sequence.resync()
        }
        val seq = (packet.get().toInt() and 0xff) or ((packet.get().toInt() and 0xff) shl 8)
        val lostBefore = sequence.lost
        if (!sequence.accept(seq)) {
            return
        }
        val gap = (sequence.lost - lostBefore).toInt()
        if (gap in 1..MAX_CONCEAL_PACKETS) {
            conceal(gap)
        }
        decoder.decode(packet) { pcm, size ->
            concealer?.remember(pcm, size)
            write(pcm, size)
        }
    }

    private fun conceal(count: Int) {
        val c = concealer ?: return
        for (i in 1..count) {
            if (!c.fill(i, concealBuffer)) {
                return
            }
            write(ByteBuffer.wrap(concealBuffer), concealBuffer.size)
            concealed++
        }
    }

    private fun write(buffer: ByteBuffer, size: Int) {
        var remaining = size
        while (remaining > 0 && running) {
            // blocking before play() would deadlock if the buffer is smaller than one chunk
            val mode = if (started) AudioTrack.WRITE_BLOCKING else AudioTrack.WRITE_NON_BLOCKING
            val written = track.write(buffer, remaining, mode)
            if (written < 0) {
                Log.w(tag, "AudioTrack.write failed: $written")
                return
            }
            remaining -= written
            if (!started) {
                framesPrefilled += written / bytesPerFrame
                if (written == 0 || framesPrefilled >= prefillFrames) {
                    startTrack()
                }
            }
        }
    }

    private fun startTrack() {
        track.play()
        started = true
        lastUnderruns = underruns()
        windowMin = Int.MAX_VALUE
        windowStart = SystemClock.elapsedRealtime()
    }

    private fun rebuffer() {
        try {
            track.pause()
        } catch (e: IllegalStateException) {
            Log.w(tag, e.stackTraceToString())
        }
        started = false
        gateOpen = false
        gateSince = 0L
        framesPrefilled = 0L
    }

    private fun underruns(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) track.underrunCount else 0

    private fun targetMs(): Double =
        max(JITTER_FACTOR * jitter.jitterMs, floorMs)
            .coerceIn(profile.minQueueMs.toDouble(), profile.maxQueueMs.toDouble())

    private fun prebuffered(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (queue.isEmpty()) {
            gateSince = 0L
            return false
        }
        if (gateSince == 0L) {
            gateSince = now
        }
        val need = ceil(max(profile.prebufferMs.toDouble(), targetMs()) / packetMs).toInt()
            .coerceIn(1, capacity - 1)
        if (queue.size >= need || now - gateSince > PREBUFFER_TIMEOUT_MS) {
            gateOpen = true
        }
        return gateOpen
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTickAt >= TICK_MS) {
            lastTickAt = now
            checkUnderruns(now)
            trimBacklog(now)
        }
        reportStats(now)
    }

    private fun checkUnderruns(now: Long) {
        if (!started) {
            return
        }
        val count = underruns()
        if (count != lastUnderruns) {
            lastUnderruns = count
            // silence also ends in an underrun, only count it when packets were still arriving
            if (now - lastArrivalMs < FLOW_WINDOW_MS) {
                lastUnderrunAt = now
                floorMs = min(floorMs + UNDERRUN_BUMP_MS, profile.maxQueueMs.toDouble())
            }
            rebuffer()
        } else if (floorMs > 0 && now - max(lastUnderrunAt, lastDecayAt) > DECAY_AFTER_MS) {
            floorMs = max(0.0, floorMs - DECAY_STEP_MS)
            lastDecayAt = now
        }
    }

    // a backlog that never drains below the target is clock drift or a catch-up burst, jitter would dip
    private fun trimBacklog(now: Long) {
        if (!started) {
            windowMin = Int.MAX_VALUE
            windowStart = now
            return
        }
        if (now - windowStart < DRIFT_WINDOW_MS) {
            return
        }
        val target = ceil(targetMs() / packetMs).toInt()
        if (windowMin != Int.MAX_VALUE && windowMin > target) {
            val excess = windowMin - target
            repeat(excess) {
                if (queue.poll() != null) {
                    trimmed++
                }
            }
            resyncPending.set(true)
        }
        windowMin = Int.MAX_VALUE
        windowStart = now
    }

    private fun reportStats(now: Long) {
        if (now - lastStatsAt < STATS_INTERVAL_MS) {
            return
        }
        lastStatsAt = now

        val parts = mutableListOf<String>()
        if (decoder != null) {
            parts += "Opus"
            parts += "lost ${sequence.lost}"
            parts += "concealed $concealed"
            parts += "late ${sequence.late}"
        } else {
            parts += "PCM"
        }
        parts += "dropped ${overflowDrops.get()}"
        parts += "trimmed $trimmed"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            parts += "underruns ${track.underrunCount}"
        }
        parts += "jitter %.0fms".format(jitter.jitterMs)
        if (running) {
            onStats(parts.joinToString(" · "))
        }
    }

    companion object {
        private const val SEQ_BYTES = 2
        private const val OPUS_PACKET_MS = 20
        private const val PCM_DATAGRAM_BYTES = 1400
        private const val MIN_QUEUE_PACKETS = 4
        private const val MAX_CONCEAL_PACKETS = 3
        private const val PREBUFFER_POLL_MS = 5L
        private const val PREBUFFER_TIMEOUT_MS = 1000L
        private const val TICK_MS = 50L
        private const val DRIFT_WINDOW_MS = 2000L
        private const val FLOW_WINDOW_MS = 500L
        private const val DECAY_AFTER_MS = 15_000L
        private const val DECAY_STEP_MS = 10.0
        private const val UNDERRUN_BUMP_MS = 20.0
        private const val JITTER_FACTOR = 4.0
        private const val STATS_INTERVAL_MS = 2000L
    }
}
