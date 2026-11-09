package com.raj.rajplayer

import android.content.Context
import android.graphics.Color

// Theme presets: rgb | pink | dark | light | gradient | none
object Theme {
    val MODES = arrayOf("rgb", "pink", "dark", "light", "gradient", "none")
    val NAMES = arrayOf("RGB", "Pink", "Dark", "Light", "Gradient", "None")

    fun hueAt(deg: Float): Int {
        val h = ((deg % 360f) + 360f) % 360f
        return Color.HSVToColor(floatArrayOf(h, 0.85f, 1f))
    }

    fun rgbNow(offset: Float): Int = hueAt((System.currentTimeMillis() / 20L % 360L).toFloat() + offset)

    fun accentOf(mode: String): Int = when (mode) {
        "rgb" -> rgbNow(0f)
        "pink" -> Color.parseColor("#FF4081")
        "light" -> Color.parseColor("#2979FF")
        "gradient" -> Color.parseColor("#E040FB")
        else -> Color.parseColor("#00E676")
    }

    fun preset(mode: String, key: String): Int {
        val light = mode == "light"
        val accent = accentOf(mode)
        return when (key) {
            Prefs.K_BG -> when (mode) {
                "light" -> Color.parseColor("#F5F7FA")
                "pink" -> Color.parseColor("#1A0A12")
                "gradient" -> Color.parseColor("#120A1E")
                "rgb" -> Color.parseColor("#05060D")
                else -> Color.parseColor("#0B0F14")
            }
            Prefs.K_ACCENT -> accent
            Prefs.K_TEXT, Prefs.K_ATEXT -> if (light) Color.parseColor("#111111") else Color.WHITE
            Prefs.K_VBAR -> Color.parseColor("#99000000")
            Prefs.K_VTEXT -> Color.WHITE
            Prefs.K_ABAR -> (accent and 0x00FFFFFF) or 0x33000000
            Prefs.K_ICON -> if (light) Color.parseColor("#111111") else Color.WHITE
            else -> Color.WHITE
        }
    }
}

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("rajplayer_ultra", Context.MODE_PRIVATE)

    // purane keys (bg/text/accent) wahi hain, isliye manual rang jas ke tas rehte hain
    const val K_BG = "bg"
    const val K_TEXT = "text"
    const val K_ACCENT = "accent"
    const val K_VBAR = "c_vbar"
    const val K_VTEXT = "c_vtext"
    const val K_ABAR = "c_abar"
    const val K_ATEXT = "c_atext"
    const val K_ICON = "c_icon"
    val COLOR_KEYS = arrayOf(K_BG, K_TEXT, K_ACCENT, K_VBAR, K_VTEXT, K_ABAR, K_ATEXT, K_ICON)

    fun themeMode(c: Context): String = p(c).getString("theme_mode", "dark") ?: "dark"

    fun setThemeMode(c: Context, mode: String, resetColors: Boolean) {
        val e = p(c).edit()
        e.putString("theme_mode", mode)
        if (resetColors) for (k in COLOR_KEYS) e.remove(k)
        e.apply()
    }

    fun color(c: Context, key: String): Int {
        val s = p(c)
        return if (s.contains(key)) s.getInt(key, 0) else Theme.preset(themeMode(c), key)
    }

    fun hasOverride(c: Context, key: String): Boolean = p(c).contains(key)
    fun setColor(c: Context, key: String, v: Int) { p(c).edit().putInt(key, v).apply() }
    fun clearColor(c: Context, key: String) { p(c).edit().remove(key).apply() }

    fun resetColors(c: Context) {
        val e = p(c).edit()
        for (k in COLOR_KEYS) e.remove(k)
        e.apply()
    }

    fun bg(c: Context): Int = color(c, K_BG)
    fun text(c: Context): Int = color(c, K_TEXT)
    fun accent(c: Context): Int = color(c, K_ACCENT)
    fun videoBarColor(c: Context): Int = color(c, K_VBAR)
    fun videoTextColor(c: Context): Int = color(c, K_VTEXT)
    fun audioBarColor(c: Context): Int = color(c, K_ABAR)
    fun audioTextColor(c: Context): Int = color(c, K_ATEXT)
    fun iconColor(c: Context): Int = color(c, K_ICON)

    fun setBg(c: Context, v: Int) = setColor(c, K_BG, v)
    fun setText(c: Context, v: Int) = setColor(c, K_TEXT, v)
    fun setAccent(c: Context, v: Int) = setColor(c, K_ACCENT, v)

    // ---- animation ----
    fun atomicOn(c: Context): Boolean = p(c).getBoolean("atomic_on", true)
    fun setAtomicOn(c: Context, v: Boolean) { p(c).edit().putBoolean("atomic_on", v).apply() }

    fun videoMarginAnim(c: Context): Boolean = p(c).getBoolean("vmargin_anim", true)
    fun setVideoMarginAnim(c: Context, v: Boolean) { p(c).edit().putBoolean("vmargin_anim", v).apply() }

    // 0.5x .. 6.0x
    fun atomSpeed(c: Context): Float = p(c).getFloat("atom_speed", 1f).coerceIn(0.5f, 6f)
    fun setAtomSpeed(c: Context, v: Float) { p(c).edit().putFloat("atom_speed", v.coerceIn(0.5f, 6f)).apply() }

    fun rgbBorder(c: Context): Boolean = p(c).getBoolean("rgb_border", false)
    fun setRgbBorder(c: Context, v: Boolean) { p(c).edit().putBoolean("rgb_border", v).apply() }

    // ---- video behaviour ----
    fun autoOrient(c: Context): Boolean = p(c).getBoolean("auto_orient", true)
    fun setAutoOrient(c: Context, v: Boolean) { p(c).edit().putBoolean("auto_orient", v).apply() }

    fun muteFast(c: Context): Boolean = p(c).getBoolean("mute_fast", true)
    fun setMuteFast(c: Context, v: Boolean) { p(c).edit().putBoolean("mute_fast", v).apply() }

    // ---- last played ----
    fun lastPlayedUri(c: Context): String? = p(c).getString("last_uri", null)
    fun setLastPlayedUri(c: Context, v: String?) { p(c).edit().putString("last_uri", v).apply() }

    fun wall(c: Context): String? = p(c).getString("wall", null)
    fun setWall(c: Context, v: String?) { p(c).edit().putString("wall", v).apply() }

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

    // audio ke repeat/shuffle
    fun repeat(c: Context): Int = p(c).getInt("repeat", 0)
    fun setRepeat(c: Context, v: Int) { p(c).edit().putInt("repeat", v).apply() }
    fun shuffle(c: Context): Boolean = p(c).getBoolean("shuffle", false)
    fun setShuffle(c: Context, v: Boolean) { p(c).edit().putBoolean("shuffle", v).apply() }

    // video aur audio ke repeat/shuffle alag-alag
    fun repeatFor(c: Context, video: Boolean): Int = if (video) p(c).getInt("v_repeat", 0) else repeat(c)
    fun setRepeatFor(c: Context, video: Boolean, v: Int) {
        if (video) p(c).edit().putInt("v_repeat", v).apply() else setRepeat(c, v)
    }
    fun shuffleFor(c: Context, video: Boolean): Boolean = if (video) p(c).getBoolean("v_shuffle", false) else shuffle(c)
    fun setShuffleFor(c: Context, video: Boolean, v: Boolean) {
        if (video) p(c).edit().putBoolean("v_shuffle", v).apply() else setShuffle(c, v)
    }

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

    // ---- bookmarks ----
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
