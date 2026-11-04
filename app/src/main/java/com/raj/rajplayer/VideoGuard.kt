package com.raj.rajplayer

import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

// Awaaz chal rahi ho par video black ho to apne aap theek karta hai
object VideoGuard {
    private val h = Handler(Looper.getMainLooper())
    private var layout: VLCVideoLayout? = null
    private var key = ""
    private var bad = 0
    private var stage = 0

    private val loop = object : Runnable {
        override fun run() {
            watch()
            h.postDelayed(this, 1000)
        }
    }

    fun start(l: VLCVideoLayout) {
        layout = l
        bad = 0
        h.removeCallbacks(loop)
        h.postDelayed(loop, 1500)
    }

    fun stop() {
        h.removeCallbacks(loop)
        layout = null
    }

    private fun watch() {
        val p = Engine.mp ?: return
        val l = layout ?: return
        if (!Engine.active || !Engine.isVideo || Engine.items.isEmpty() || !p.isPlaying) {
            bad = 0
            return
        }
        val k = Engine.cur().toString()
        if (k != key) {
            key = k
            stage = 0
            bad = 0
        }
        if (!Engine.voutEver || p.getVideoTracksCount() <= 0 || p.getTime() < 800) {
            bad = 0
            return
        }
        if (Engine.vout > 0) {
            bad = 0
            stage = 0
            return
        }
        bad++
        if (bad < 2) return
        bad = 0
        if (stage == 0) {
            stage = 1
            revive(p, l)
        } else if (stage == 1) {
            stage = 2
            Engine.forceSoft = true
            Engine.loadAt(l.context, Engine.idx)
        }
    }

    private fun revive(p: MediaPlayer, l: VLCVideoLayout) {
        try {
            p.detachViews()
        } catch (e: Exception) {
        }
        try {
            p.attachViews(l, null, true, false)
        } catch (e: Exception) {
        }
        try {
            val t = p.getVideoTrack()
            if (t >= 0) {
                p.setVideoTrack(-1)
                p.setVideoTrack(t)
            }
        } catch (e: Exception) {
        }
    }
}
