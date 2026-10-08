package io.github.mkckr0.audio_share_app.service

enum class LatencyProfile(val prebufferMs: Int, val minQueueMs: Int, val maxQueueMs: Int) {
    LOW(20, 20, 100),
    BALANCED(60, 40, 200),
    STABLE(150, 100, 400);

    companion object {
        fun fromIndex(index: Int): LatencyProfile = values().getOrElse(index) { BALANCED }
    }
}
