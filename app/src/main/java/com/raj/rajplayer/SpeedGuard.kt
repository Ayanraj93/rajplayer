package com.raj.rajplayer

// Playback asli speed se pichhad raha ho to pakadta hai.
// sample() ka jawab: 0 = kuch nahi, 1 = sync dobara, 2 = speed ghatao, 3 = decoder badlo
class SpeedGuard {
    private var lastWall = 0L
    private var lastMedia = 0L
    private var bad = 0
    private var stage = 0
    private var cool = 0L

    fun reset() {
        lastWall = 0L
        bad = 0
    }

    fun newItem() {
        reset()
        stage = 0
        cool = 0L
    }

    fun sample(now: Long, media: Long, playing: Boolean, rate: Float): Int {
        if (!playing) {
            lastWall = 0L
            bad = 0
            return 0
        }
        if (lastWall == 0L) {
            lastWall = now
            lastMedia = media
            return 0
        }
        val dw = now - lastWall
        val dm = media - lastMedia
        lastWall = now
        lastMedia = media
        // seek / jump ko ignore karo
        if (dw < 400L || dm < 0L || dm > dw * rate * 3f) return 0
        val ratio = dm / (dw * rate)
        if (ratio < 0.8f) bad++ else if (bad > 0) bad--
        if (bad < 3 || now < cool) return 0
        bad = 0
        cool = now + 8000L
        stage++
        return when (stage) {
            1 -> 1
            2 -> if (rate > 1.01f) 2 else 3
            3 -> 3
            else -> if (rate > 1.01f) 2 else 0
        }
    }
}
