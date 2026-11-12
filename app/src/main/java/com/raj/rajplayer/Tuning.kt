package com.raj.rajplayer

import android.content.Context

/** Media3 delegates codec choice to Android MediaCodec; VLC-specific HW/HW+ flags are removed. */
object Tuning {
    private fun p(c: Context) = c.getSharedPreferences("rajplayer_ultra", Context.MODE_PRIVATE)
    fun decMode(@Suppress("UNUSED_PARAMETER") c: Context): String = "media3"
    fun decLabel(@Suppress("UNUSED_PARAMETER") m: String): String = "Media3 / Android MediaCodec"
    fun autoFix(c: Context): Boolean = p(c).getBoolean("auto_fix", true)
    fun setAutoFix(c: Context, v: Boolean) { p(c).edit().putBoolean("auto_fix", v).apply() }
}
