package com.raj.rajplayer

import android.os.Handler
import android.os.Looper
import android.view.View

/** Media3 owns surface recovery and late-frame dropping; this guard only observes health. */
object VideoGuard {
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var lastDrops = 0L
    private val loop = object : Runnable {
        override fun run() {
            val now = Engine.droppedFrames
            if (Engine.active && Engine.isVideo && Engine.isPlaying() && now > lastDrops) {
                // Intentionally do not detach/re-attach the surface: doing so can cause black frames.
                lastDrops = now
            }
            handler.postDelayed(this, 1500L)
        }
    }
    fun start(v: View) { view = v; lastDrops = Engine.droppedFrames; handler.removeCallbacks(loop); handler.postDelayed(loop, 1500L) }
    fun stop() { handler.removeCallbacks(loop); view = null }
}
