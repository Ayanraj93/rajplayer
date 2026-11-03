package com.raj.rajplayer

import android.content.Context

// Audio aur Video ke Repeat/Shuffle alag-alag save hote hain
object Modes {
    private fun p(c: Context) = c.getSharedPreferences("rajplayer_ultra", Context.MODE_PRIVATE)

    fun repeat(c: Context, video: Boolean): Int =
        if (video) p(c).getInt("v_repeat", 0) else Prefs.repeat(c)

    fun setRepeat(c: Context, video: Boolean, v: Int) {
        if (video) p(c).edit().putInt("v_repeat", v).apply() else Prefs.setRepeat(c, v)
    }

    fun shuffle(c: Context, video: Boolean): Boolean =
        if (video) p(c).getBoolean("v_shuffle", false) else Prefs.shuffle(c)

    fun setShuffle(c: Context, video: Boolean, v: Boolean) {
        if (video) p(c).edit().putBoolean("v_shuffle", v).apply() else Prefs.setShuffle(c, v)
    }
}
