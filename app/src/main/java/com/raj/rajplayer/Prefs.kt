package com.raj.rajplayer

import android.content.Context
import android.graphics.Color

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("rajplayer", Context.MODE_PRIVATE)

    fun bg(c: Context): Int = p(c).getInt("bg", Color.parseColor("#101418"))
    fun text(c: Context): Int = p(c).getInt("text", Color.WHITE)
    fun accent(c: Context): Int = p(c).getInt("accent", Color.parseColor("#00C853"))
    fun wall(c: Context): String? = p(c).getString("wall", null)

    fun setBg(c: Context, v: Int) { p(c).edit().putInt("bg", v).apply() }
    fun setText(c: Context, v: Int) { p(c).edit().putInt("text", v).apply() }
    fun setAccent(c: Context, v: Int) { p(c).edit().putInt("accent", v).apply() }
    fun setWall(c: Context, v: String?) { p(c).edit().putString("wall", v).apply() }

    fun pos(c: Context, key: String): Long = p(c).getLong("pos_" + key, 0L)
    fun setPos(c: Context, key: String, v: Long) {
        val e = p(c).edit()
        if (v <= 0L) e.remove("pos_" + key) else e.putLong("pos_" + key, v)
        e.apply()
    }

    fun hw(c: Context): Boolean = p(c).getBoolean("hw", true)
    fun setHw(c: Context, v: Boolean) { p(c).edit().putBoolean("hw", v).apply() }

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
