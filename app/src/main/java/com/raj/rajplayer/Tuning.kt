package com.raj.rajplayer

import android.content.Context
import org.videolan.libvlc.Media

// Zero-lag tuning: decoder modes (Auto / HW / HW+ / SW) + LibVLC options
object Tuning {
    private fun p(c: Context) = c.getSharedPreferences("rajplayer_ultra", Context.MODE_PRIVATE)

    // auto | hw | hwplus | sw
    fun decMode(c: Context): String = p(c).getString("dec_mode", "auto") ?: "auto"
    fun setDecMode(c: Context, m: String) { p(c).edit().putString("dec_mode", m).apply() }

    // lag aaye to app khud theek kare
    fun autoFix(c: Context): Boolean = p(c).getBoolean("auto_fix", true)
    fun setAutoFix(c: Context, v: Boolean) { p(c).edit().putBoolean("auto_fix", v).apply() }

    fun decLabel(m: String): String = when (m) {
        "hw" -> "HW decoder"
        "hwplus" -> "HW+ decoder (forced hardware)"
        "sw" -> "SW decoder (software)"
        else -> "Auto (HW, falls back to SW)"
    }

    // jis file par auto-fix ne decoder badla, us file ke liye wahi rahega
    private val swFiles = HashSet<String>()
    private val hwFiles = HashSet<String>()

    fun forceSw(key: String) {
        swFiles.add(key)
        hwFiles.remove(key)
    }

    fun forceHw(key: String) {
        hwFiles.add(key)
        swFiles.remove(key)
    }

    fun isSw(key: String): Boolean = swFiles.contains(key)

    fun clearOverrides() {
        swFiles.clear()
        hwFiles.clear()
    }

    // LibVLC ke options: tez start + smooth sync
    fun options(): ArrayList<String> {
        val o = ArrayList<String>()
        o.add("--file-caching=300")
        o.add("--network-caching=1500")
        o.add("--clock-jitter=0")
        o.add("--clock-synchro=0")
        o.add("--audio-time-stretch")
        o.add("--no-stats")
        return o
    }

    fun applyDecoder(m: Media, c: Context, key: String) {
        if (swFiles.contains(key)) {
            m.setHWDecoderEnabled(false, false)
            return
        }
        if (hwFiles.contains(key)) {
            m.setHWDecoderEnabled(true, false)
            return
        }
        when (decMode(c)) {
            "hw" -> m.setHWDecoderEnabled(true, false)
            "hwplus" -> m.setHWDecoderEnabled(true, true)
            "sw" -> m.setHWDecoderEnabled(false, false)
            else -> m.setHWDecoderEnabled(true, false)
        }
    }
}
