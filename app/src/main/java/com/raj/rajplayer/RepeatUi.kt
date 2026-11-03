package com.raj.rajplayer

import android.content.Context
import android.widget.TextView

object RepeatUi {
    fun paint(c: Context, t: TextView) {
        val r = Prefs.repeat(c)
        t.text = if (r == 2) "\uD83D\uDD02" else "\uD83D\uDD01"
        t.alpha = if (r == 0) 0.35f else 1f
    }

    fun cycle(c: Context, t: TextView): String {
        Prefs.setRepeat(c, (Prefs.repeat(c) + 1) % 3)
        paint(c, t)
        return when (Prefs.repeat(c)) {
            1 -> "Repeat: All"
            2 -> "Repeat: One"
            else -> "Repeat: Off"
        }
    }
}
