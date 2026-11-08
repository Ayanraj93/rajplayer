package com.raj.rajplayer

import android.media.AudioManager
import android.os.Handler
import android.os.Looper

object Focus {
    private val h = Handler(Looper.getMainLooper())
    private var pausedByFocus = false

    // true = abhi pause karo
    fun handle(c: Int): Boolean {
        if (c == AudioManager.AUDIOFOCUS_LOSS) {
            pausedByFocus = false
            return true
        }
        if (c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (Engine.isPlaying()) pausedByFocus = true
            return true
        }
        if (c == AudioManager.AUDIOFOCUS_GAIN && pausedByFocus) {
            pausedByFocus = false
            h.postDelayed({
                val p = Engine.mp
                if (p != null && Engine.active && !p.isPlaying) {
                    p.play()
                    Engine.notifyChanged()
                }
            }, 300)
        }
        return false
    }
}
