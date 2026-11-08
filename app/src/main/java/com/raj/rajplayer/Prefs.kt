package com.raj.rajplayer

import android.content.Context
import android.graphics.Color

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("rajplayer_ultra", Context.MODE_PRIVATE)

    fun bg(c: Context): Int = p(c).getInt("bg", Color.parseColor("#0B0F14"))
    fun text(c: Context): Int = p(c).getInt("text", Color.WHITE)
    fun accent(c: Context): Int = p(c).getInt("accent", Color.parseColor("#00E676"))
    
    // Separate Color Pickers
    fun videoTitleBarColor(c: Context): Int = p(c).getInt("vid_title_bar_col", Color.parseColor("#99000000"))
    fun videoTitleColor(c: Context): Int = p(c).getInt("vid_title_col", Color.WHITE)
    fun audioTitleColor(c: Context): Int = p(c).getInt("aud_title_col", Color.WHITE)
    fun audioTitleBarColor(c: Context): Int = p(c).getInt("aud_title_bar_col", Color.parseColor("#99000000"))
    fun iconColor(c: Context): Int = p(c).getInt("icon_col", Color.WHITE)

    fun setBg(c: Context, v: Int) { p(c).edit().putInt("bg", v).apply() }
    fun setText(c: Context, v: Int) { p(c).edit().putInt("text", v).apply() }
    fun setAccent(c: Context, v: Int) { p(c).edit().putInt("accent", v).apply() }
    
    fun setVideoTitleBarColor(c: Context, v: Int) { p(c).edit().putInt("vid_title_bar_col", v).apply() }
    fun setVideoTitleColor(c: Context, v: Int) { p(c).edit().putInt("vid_title_col", v).apply() }
    fun setAudioTitleColor(c: Context, v: Int) { p(c).edit().putInt("aud_title_col", v).apply() }
    fun setAudioTitleBarColor(c: Context, v: Int) { p(c).edit().putInt("aud_title_bar_col", v).apply() }
    fun setIconColor(c: Context, v: Int) { p(c).edit().putInt("icon_col", v).apply() }

    fun wall(c: Context): String? = p(c).getString("wall", null)
    fun setWall(c: Context, v: String?) { p(c).edit().putString("wall", v).apply() }

    fun rgbAnim(c: Context): Boolean = p(c).getBoolean("rgb_anim", true)
    fun setRgbAnim(c: Context, v: Boolean) { p(c).edit().putBoolean("rgb_anim", v).apply() }

    fun atomicAnim(c: Context): Boolean = p(c).getBoolean("atomic_anim", true)
    fun setAtomicAnim(c: Context, v: Boolean) { p(c).edit().putBoolean("atomic_anim", v).apply() }

    fun themeMode(c: Context): String = p(c).getString("theme_mode", "rgb") ?: "rgb" // rgb, dark, light, pink, gradient, none
    fun setThemeMode(c: Context, v: String) { p(c).edit().putString("theme_mode", v).apply() }

    fun animSpeed(c: Context): Float = p(c).getFloat("anim_speed", 1.0f)
    fun setAnimSpeed(c: Context, v: Float) { p(c).edit().putFloat("anim_speed", v).apply() }

    fun pos(c: Context, key: String): Long = p(c).getLong("pos_" + key, 0L)
    fun setPos(c: Context, key: String, v: Long) {
        val e = p(c).edit()
        if (v <= 0L) e.remove("pos_" + key) else e.putLong("pos_" + key, v)
        e.apply()
    }

    fun hw(c: Context): Boolean = p(c).getBoolean("hw", true)
    fun setHw(c: Context, v: Boolean) { p(c).edit().putBoolean("hw", v).apply() }

    fun seekSec(c: Context): Int = p(c).getInt("seek_sec", 10)
    fun setSeekSec(c: Context, v: Int) { p(c).edit().putInt("seek_sec", v).apply() }

    fun sortMode(c: Context): Int = p(c).getInt("sort_mode", 0)
    fun setSortMode(c: Context, v: Int) { p(c).edit().putInt("sort_mode", v).apply() }

    fun sortAsc(c: Context): Boolean = p(c).getBoolean("sort_asc", false)
    fun setSortAsc(c: Context, v: Boolean) { p(c).edit().putBoolean("sort_asc", v).apply() }

    fun gridView(c: Context): Boolean = p(c).getBoolean("grid_view", false)
    fun setGridView(c: Context, v: Boolean) { p(c).edit().putBoolean("grid_view", v).apply() }

    fun bgAudio(c: Context): Boolean = p(c).getBoolean("bg_audio2", true)
    fun setBgAudio(c: Context, v: Boolean) { p(c).edit().putBoolean("bg_audio2", v).apply() }

    fun repeat(c: Context): Int = p(c).getInt("repeat", 0)
    fun setRepeat(c: Context, v: Int) { p(c).edit().putInt("repeat", v).apply() }

    fun shuffle(c: Context): Boolean = p(c).getBoolean("shuffle", false)
    fun setShuffle(c: Context, v: Boolean) { p(c).edit().putBoolean("shuffle", v).apply() }

    fun favs(c: Context): Set<String> {
        return p(c).getStringSet("fav_uris", emptySet()) ?: emptySet()
    }

    fun toggleFav(c: Context, uriStr: String): Boolean {
        val set = favs(c).toMutableSet()
        val added = if (set.contains(uriStr)) {
            set.remove(uriStr)
            false
        } else {
            set.add(uriStr)
            true
        }
        p(c).edit().putStringSet("fav_uris", set).apply()
        return added
    }

    fun isFav(c: Context, uriStr: String): Boolean = favs(c).contains(uriStr)

    fun bms(c: Context, key: String): List<Long> {
        val s = p(c).getString("bm_" + key, "") ?: ""
        if (s.isEmpty()) return emptyList()
        return s.split(",").mapNotNull { it.toLongOrNull() }
    }

    fun setBms(c: Context, key: String, v: List<Long>) {
        val e = p(c).edit()
        if (v.isEmpty()) e.remove("bm_" + key) else e.putString("bm_" + key, v.joinToString(","))
        e.apply()
    }
}
