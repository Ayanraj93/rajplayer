package com.raj.rajplayer

import android.content.Context

object Tuning {
    private fun p(c: Context) =
        c.getSharedPreferences("rajplayer_ultra", Context.MODE_PRIVATE)

    const val DEC_MEDIA3 = "media3"
    const val DEC_MPV = "mpv"

    fun decMode(c: Context): String =
        p(c).getString("decoder_mode", DEC_MEDIA3)
            ?.takeIf { it == DEC_MEDIA3 || it == DEC_MPV }
            ?: DEC_MEDIA3

    fun setDecMode(c: Context, mode: String) {
        if (mode != DEC_MEDIA3 && mode != DEC_MPV) return
        p(c).edit().putString("decoder_mode", mode).apply()
    }

    fun decLabel(mode: String): String = when (mode) {
        DEC_MPV -> "MPV"
        else -> "Media3 / Android MediaCodec"
    }

    fun autoFix(c: Context): Boolean =
        p(c).getBoolean("auto_fix", true)

    fun setAutoFix(c: Context, v: Boolean) {
        p(c).edit().putBoolean("auto_fix", v).apply()
    }
}
