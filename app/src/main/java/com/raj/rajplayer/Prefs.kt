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
}
