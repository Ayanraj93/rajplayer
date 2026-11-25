package com.raj.rajplayer

import android.content.Context

object Tuning {
    private fun p(c: Context) =
        c.getSharedPreferences("rajplayer_ultra", Context.MODE_PRIVATE)

    const val DEC_MEDIA3 = "media3"

    fun decMode(c: Context): String = DEC_MEDIA3

    fun setDecMode(c: Context, mode: String) {
        p(c).edit().putString("decoder_mode", DEC_MEDIA3).apply()
    }

    fun decLabel(mode: String): String = "Media3 / Android MediaCodec"

    fun autoFix(c: Context): Boolean =
        p(c).getBoolean("auto_fix", true)

    fun setAutoFix(c: Context, v: Boolean) {
        p(c).edit().putBoolean("auto_fix", v).apply()
    }
}
