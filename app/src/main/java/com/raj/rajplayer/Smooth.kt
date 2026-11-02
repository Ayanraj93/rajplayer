package com.raj.rajplayer

import android.os.Handler
import android.os.Looper

private val smoothH = Handler(Looper.getMainLooper())

// Pause ke baad resume par decoder/clock ko dobara sync karta hai
fun Engine.resync() {
    smoothH.postDelayed({
        val m = mp
        if (m != null && m.isPlaying) m.setTime(m.getTime())
    }, 250)
}
