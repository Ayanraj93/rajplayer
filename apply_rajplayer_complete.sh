#!/data/data/com.termux/files/usr/bin/bash
set -e
P=~/RajPlayer
PK=$P/app/src/main/java/com/raj/rajplayer
MF=$P/app/src/main/AndroidManifest.xml

[ -d "$PK" ] || { echo "Error: $PK not found!"; exit 1; }

# ==================== 1. AndroidManifest.xml ====================
cat > "$MF" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application
        android:label="RajPlayer"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:usesCleartextTraffic="true"
        android:theme="@android:style/Theme.Material.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="video/*" />
                <data android:mimeType="audio/*" />
            </intent-filter>
        </activity>

        <activity
            android:name=".PlayerActivity"
            android:exported="true"
            android:supportsPictureInPicture="true"
            android:configChanges="orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="content" />
                <data android:scheme="file" />
                <data android:scheme="http" />
                <data android:scheme="https" />
                <data android:mimeType="video/*" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="video/*" />
            </intent-filter>
        </activity>

        <activity
            android:name=".AudioActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="content" />
                <data android:scheme="file" />
                <data android:scheme="http" />
                <data android:scheme="https" />
                <data android:mimeType="audio/*" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="audio/*" />
            </intent-filter>
        </activity>

        <service
            android:name=".PlaybackService"
            android:exported="false"
            android:foregroundServiceType="mediaPlayback" />
    </application>
</manifest>
EOF

# ==================== 2. Prefs.kt ====================
cat > "$PK/Prefs.kt" <<'EOF'
package com.raj.rajplayer

import android.content.Context
import android.graphics.Color

object Theme {
    val MODES = arrayOf("rgb", "pink", "dark", "light", "gradient", "none")
    val NAMES = arrayOf("RGB", "Pink", "Dark", "Light", "Gradient", "None")

    fun hueAt(deg: Float): Int {
        val h = ((deg % 360f) + 360f) % 360f
        return Color.HSVToColor(floatArrayOf(h, 0.85f, 1f))
    }

    fun rgbNow(offset: Float): Int =
        hueAt((System.currentTimeMillis() / 20L % 360L).toFloat() + offset)

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

    // Global Atomic Animation
    fun atomicOn(c: Context): Boolean = p(c).getBoolean("atomic_on", true)
    fun setAtomicOn(c: Context, v: Boolean) { p(c).edit().putBoolean("atomic_on", v).apply() }

    // Independent Video Player Black Margin Animation Toggle
    fun playerMarginAnim(c: Context): Boolean = p(c).getBoolean("player_margin_anim", true)
    fun setPlayerMarginAnim(c: Context, v: Boolean) { p(c).edit().putBoolean("player_margin_anim", v).apply() }

    // Animation Speed: 0.5x up to 6.0x
    fun atomSpeed(c: Context): Float = p(c).getFloat("atom_speed", 1f).coerceIn(0.5f, 6f)
    fun setAtomSpeed(c: Context, v: Float) { p(c).edit().putFloat("atom_speed", v.coerceIn(0.5f, 6f)).apply() }

    fun rgbBorder(c: Context): Boolean = p(c).getBoolean("rgb_border", false)
    fun setRgbBorder(c: Context, v: Boolean) { p(c).edit().putBoolean("rgb_border", v).apply() }

    // Last Played Media URI Highlight Tracking
    fun lastPlayed(c: Context): String = p(c).getString("last_played_uri", "") ?: ""
    fun setLastPlayed(c: Context, uriStr: String) { p(c).edit().putString("last_played_uri", uriStr).apply() }

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
EOF

# ==================== 3. AtomicView.kt ====================
cat > "$PK/AtomicView.kt" <<'EOF'
package com.raj.rajplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.view.Choreographer
import android.view.View
import java.lang.ref.WeakReference
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class AtomicView(ctx: Context, private val isVideoMarginMode: Boolean = false) : View(ctx) {

    private class Node(val bx: Float, val by: Float, val p1: Float, val p2: Float, val sp: Float) {
        var x = bx
        var y = by
    }

    private val d = ctx.resources.displayMetrics.density
    private val nodes = ArrayList<Node>()
    private var bonds = IntArray(0)
    private var spacing = 0f
    private var time = 0f
    private var last = 0L
    private var running = false
    private var active = true
    private var mode = "dark"
    private var speed = 1f

    private val bondPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hsv = floatArrayOf(0f, 0.85f, 1f)
    private val excluded = ArrayList<WeakReference<View>>()
    private val rects = ArrayList<Rect>()
    private val loc = IntArray(2)
    private val loc2 = IntArray(2)
    private val tmp = Rect()

    private val cPink = Color.parseColor("#FF4081")
    private val cDark = Color.parseColor("#00E676")
    private val cLight = Color.parseColor("#2979FF")
    private val cG1 = Color.parseColor("#E040FB")
    private val cG2 = Color.parseColor("#00E5FF")

    var maxAlpha = 140

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        bondPaint.style = Paint.Style.STROKE
        bondPaint.strokeWidth = 1.4f * d
        bondPaint.strokeCap = Paint.Cap.ROUND
        nodePaint.style = Paint.Style.FILL
        applyPrefs()
    }

    fun applyPrefs() {
        mode = Prefs.themeMode(context)
        speed = Prefs.atomSpeed(context)
        active = if (isVideoMarginMode) {
            Prefs.playerMarginAnim(context) && mode != "none"
        } else {
            Prefs.atomicOn(context) && mode != "none"
        }
        visibility = if (active) VISIBLE else GONE
        syncLoop()
        invalidate()
    }

    fun setThemeMode(m: String) {
        mode = m
        applyPrefs()
    }

    fun setSpeedMultiplier(m: Float) {
        speed = m.coerceIn(0.5f, 6f)
    }

    fun excludeView(v: View) {
        excluded.add(WeakReference(v))
    }

    fun excludeRect(r: Rect) {
        rects.add(Rect(r))
    }

    fun clearExclusions() {
        excluded.clear()
        rects.clear()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        build(w, h)
    }

    private fun build(w: Int, h: Int) {
        nodes.clear()
        if (w <= 0 || h <= 0) return
        spacing = 58f * d
        val cols = (w / spacing).toInt() + 2
        val rows = (h / spacing).toInt() + 2
        val rnd = java.util.Random(7)
        for (j in -1..rows) {
            for (i in -1..cols) {
                if (((i + j) and 1) == 0) {
                    nodes.add(
                        Node(
                            i * spacing, j * spacing,
                            rnd.nextFloat() * 6.28f, rnd.nextFloat() * 6.28f,
                            0.5f + rnd.nextFloat() * 0.9f
                        )
                    )
                }
            }
        }
        val lim = spacing * 1.55f
        val pairs = ArrayList<Int>()
        for (a in 0 until nodes.size) {
            for (b in (a + 1) until nodes.size) {
                val dx = nodes[a].bx - nodes[b].bx
                val dy = nodes[a].by - nodes[b].by
                if (dx * dx + dy * dy <= lim * lim) {
                    pairs.add(a)
                    pairs.add(b)
                }
            }
        }
        bonds = pairs.toIntArray()
    }

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (last == 0L) last = frameTimeNanos
            val dt = (frameTimeNanos - last) / 1_000_000_000f
            if (dt >= 0.030f) {
                last = frameTimeNanos
                time += dt.coerceAtMost(0.1f) * speed
                move()
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun syncLoop() {
        val want = active && isAttachedToWindow && windowVisibility == VISIBLE
        if (want && !running) {
            running = true
            last = 0L
            Choreographer.getInstance().postFrameCallback(frameCb)
        } else if (!want && running) {
            running = false
            Choreographer.getInstance().removeFrameCallback(frameCb)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        syncLoop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncLoop()
    }

    private fun move() {
        val r = spacing * 0.24f
        for (n in nodes) {
            n.x = n.bx + cos(time * n.sp + n.p1) * r
            n.y = n.by + sin(time * n.sp * 0.85f + n.p2) * r
        }
    }

    private fun hue(deg: Float): Int {
        hsv[0] = ((deg % 360f) + 360f) % 360f
        return Color.HSVToColor(hsv)
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun colorAt(x: Float): Int {
        val w = if (width > 0) width.toFloat() else 1f
        return when (mode) {
            "rgb" -> hue(time * 60f + x / w * 140f)
            "pink" -> cPink
            "light" -> cLight
            "gradient" -> lerp(cG1, cG2, (x / w).coerceIn(0f, 1f))
            else -> cDark
        }
    }

    private fun withAlpha(c: Int, a: Int): Int = (a.coerceIn(0, 255) shl 24) or (c and 0x00FFFFFF)

    @Suppress("DEPRECATION")
    private fun clipOutCompat(canvas: Canvas, rect: Rect) {
        if (Build.VERSION.SDK_INT >= 26) {
            canvas.clipOutRect(rect)
        } else {
            canvas.clipRect(rect, android.graphics.Region.Op.DIFFERENCE)
        }
    }

    private fun clipExclusions(canvas: Canvas) {
        if (excluded.isEmpty() && rects.isEmpty()) return
        getLocationInWindow(loc)
        val iter = excluded.iterator()
        while (iter.hasNext()) {
            val v = iter.next().get()
            if (v == null) {
                iter.remove()
                continue
            }
            if (!v.isShown || v.width <= 0 || v.height <= 0) continue
            v.getLocationInWindow(loc2)
            val l = loc2[0] - loc[0]
            val t = loc2[1] - loc[1]
            tmp.set(l, t, l + v.width, t + v.height)
            clipOutCompat(canvas, tmp)
        }
        for (r in rects) clipOutCompat(canvas, r)
    }

    override fun onDraw(canvas: Canvas) {
        if (!active || nodes.isEmpty()) return
        val save = canvas.save()
        clipExclusions(canvas)

        val maxD = spacing * 1.9f
        var i = 0
        while (i < bonds.size) {
            val a = nodes[bonds[i]]
            val b = nodes[bonds[i + 1]]
            val dist = hypot(a.x - b.x, a.y - b.y)
            if (dist < maxD) {
                val al = ((1f - dist / maxD) * maxAlpha).toInt()
                bondPaint.color = withAlpha(colorAt((a.x + b.x) * 0.5f), al)
                canvas.drawLine(a.x, a.y, b.x, b.y, bondPaint)
            }
            i += 2
        }

        val pulse = 0.5f + 0.5f * sin(time * 2f)
        for (n in nodes) {
            val c = colorAt(n.x)
            nodePaint.color = withAlpha(c, (maxAlpha * 0.38f).toInt())
            canvas.drawCircle(n.x, n.y, (5f + 2f * pulse) * d, nodePaint)
            nodePaint.color = withAlpha(c, (maxAlpha * 1.45f).toInt())
            canvas.drawCircle(n.x, n.y, 2.3f * d, nodePaint)
        }
        canvas.restoreToCount(save)
    }
}

class RgbBorderView(ctx: Context) : View(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val r = RectF()
    private var sweep: SweepGradient? = null
    private var angle = 0f
    private var running = false
    private var last = 0L

    private val colors = intArrayOf(
        0xFFFF1744.toInt(), 0xFFFF9100.toInt(), 0xFFFFEA00.toInt(), 0xFF00E676.toInt(),
        0xFF00E5FF.toInt(), 0xFF3D5AFE.toInt(), 0xFFD500F9.toInt(), 0xFFFF1744.toInt()
    )

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        line.style = Paint.Style.STROKE
        glow.style = Paint.Style.STROKE
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        sweep = SweepGradient(w / 2f, h / 2f, colors, null)
    }

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (last == 0L) last = frameTimeNanos
            val dt = (frameTimeNanos - last) / 1_000_000_000f
            last = frameTimeNanos
            angle = (angle + dt.coerceAtMost(0.1f) * 90f * Prefs.atomSpeed(context)) % 360f
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun syncLoop() {
        val want = isAttachedToWindow && windowVisibility == VISIBLE
        if (want && !running) {
            running = true
            last = 0L
            Choreographer.getInstance().postFrameCallback(frameCb)
        } else if (!want && running) {
            running = false
            Choreographer.getInstance().removeFrameCallback(frameCb)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        syncLoop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncLoop()
    }

    override fun onDraw(canvas: Canvas) {
        val s = sweep ?: return
        m.setRotate(angle, width / 2f, height / 2f)
        s.setLocalMatrix(m)
        line.shader = s
        glow.shader = s
        val sw = 3f * d
        val rad = 26f * d
        r.set(sw / 2f, sw / 2f, width - sw / 2f, height - sw / 2f)
        glow.strokeWidth = sw * 5f
        glow.alpha = 35
        canvas.drawRoundRect(r, rad, rad, glow)
        glow.strokeWidth = sw * 2.5f
        glow.alpha = 80
        canvas.drawRoundRect(r, rad, rad, glow)
        line.strokeWidth = sw
        canvas.drawRoundRect(r, rad, rad, line)
    }
}
EOF

# ==================== 4. MainActivity.kt ====================
cat > "$PK/MainActivity.kt" <<'EOF'
package com.raj.rajplayer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    data class V(val name: String, val uri: Uri, val dur: Long, val size: Long, val folder: String, val date: Long)
    data class Item(val title: String, val sub: String, val uri: Uri?, val badge: String, val tag: String)
    class H(val rowRoot: LinearLayout, val check: TextView, val thumb: ImageView, val badge: TextView, val title: TextView, val sub: TextView)

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var frame: FrameLayout
    private lateinit var listHolder: FrameLayout
    private lateinit var miniBar: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniPlay: TextView
    private var selCountTv: TextView? = null
    private var curAdapter: BaseAdapter? = null
    private var atomic: AtomicView? = null
    private var settingsSv: ScrollView? = null
    private var settingsY = 0

    @Volatile private var videos: List<V> = emptyList()
    @Volatile private var audios: List<V> = emptyList()
    @Volatile private var loading = false
    private var shown: List<V> = emptyList()
    private var inSettings = false
    private var asked = false
    private var dirty = true
    private var tab = 0
    private var openFolder: String? = null
    private var searchOn = false
    private var query = ""
    private var selectMode = false
    private val selected = LinkedHashSet<String>()
    private val rgbTargets = ArrayList<(Int) -> Unit>()

    private val ui = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(120)
    private val pool = Executors.newFixedThreadPool(3)
    private val loader = Executors.newSingleThreadExecutor()

    private val palette = listOf(
        "#000000", "#0B0F14", "#121820", "#1A222D", "#1E1E2E", "#2D1B36", "#0F2A3F", "#FFFFFF",
        "#F5F7FA", "#FFD700", "#FF9800", "#FF5252", "#FF4081", "#E040FB", "#7C4DFF", "#448AFF",
        "#2979FF", "#00E5FF", "#00E676", "#76FF03"
    )

    private val miniTick = object : Runnable {
        override fun run() {
            refreshMini()
            ui.postDelayed(this, 700)
        }
    }

    private val rgbTick = object : Runnable {
        override fun run() {
            if (rgbTargets.isNotEmpty()) {
                val col = Theme.rgbNow(0f)
                for (t in rgbTargets) t(col)
                ui.postDelayed(this, 70)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frame = FrameLayout(this)
        setContentView(frame)
        Engine.bgAudio = Prefs.bgAudio(this)
        if (handleIncoming(intent)) finish()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (!hasPerm() && !asked) {
            asked = true
            reqPerms()
        }
        dirty = true
        if (inSettings) refreshSettings() else showHome()
        ui.post(miniTick)
        if (hasPerm()) Power.askOnce(this)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(miniTick)
        ui.removeCallbacks(rgbTick)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        dirty = true
        showHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selectMode) {
            exitSelect()
        } else if (inSettings) {
            settingsY = 0
            showHome()
        } else if (openFolder != null) {
            openFolder = null
            showHome()
        } else {
            super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9) {
            if (resultCode == RESULT_OK) afterDelete() else toast("Delete cancelled")
            return
        }
        if (resultCode != RESULT_OK) return
        val u = data?.data ?: return
        if (requestCode == 2) openUri(u, null)
        if (requestCode == 3) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            Prefs.setWall(this, u.toString())
            refreshSettings()
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIncoming(i: Intent?): Boolean {
        if (i == null) return false
        var u: Uri? = null
        if (i.action == Intent.ACTION_SEND) {
            u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            val cd = i.clipData
            if (u == null && cd != null && cd.itemCount > 0) u = cd.getItemAt(0).uri
        } else if (i.action == Intent.ACTION_VIEW) {
            u = i.data
        }
        if (u == null) return false
        openUri(u, i.type)
        return true
    }

    private fun looksAudio(u: Uri): Boolean {
        val n = (u.lastPathSegment ?: "").lowercase()
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav") ||
                n.endsWith(".ogg") || n.endsWith(".flac") || n.endsWith(".aac") || n.endsWith(".opus")
    }

    private fun openUri(u: Uri, mime: String?) {
        Prefs.setLastPlayed(this, u.toString())
        val t = mime ?: contentResolver.getType(u) ?: ""
        val aud = t.startsWith("audio/") || (t.isEmpty() && looksAudio(u))
        val cls: Class<*> = if (aud) AudioActivity::class.java else PlayerActivity::class.java
        val i = Intent(this, cls)
        i.data = u
        i.clipData = ClipData.newRawUri("media", u)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun perm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun audioPerm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPerm() =
        checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(audioPerm()) == PackageManager.PERMISSION_GRANTED

    private fun reqPerms() {
        val l = ArrayList<String>()
        l.add(perm())
        if (audioPerm() != perm()) l.add(audioPerm())
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(l.toTypedArray(), 1)
    }

    private fun openAppSettings() {
        val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        i.data = Uri.fromParts("package", packageName, null)
        startActivity(i)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun contrast(c: Int) = if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE

    private fun isAudio(u: Uri) = u.toString().contains("/audio/") || looksAudio(u)

    private fun label(t: String, size: Float, color: Int): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        v.setPadding(0, dp(8), 0, dp(8))
        return v
    }

    private fun button(t: String, ac: Int, f: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.setBackgroundColor(ac)
        b.setTextColor(contrast(ac))
        b.setOnClickListener { f() }
        return b
    }

    private fun sym(t: String, color: Int, f: () -> Unit): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 22f
        v.setTextColor(color)
        v.setPadding(dp(10), dp(8), dp(10), dp(8))
        v.setOnClickListener { f() }
        return v
    }

    private fun tabBtn(t: String, sel: Boolean, ac: Int, tc: Int, f: () -> Unit): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = 14f
        v.gravity = Gravity.CENTER
        v.setPadding(0, dp(10), 0, dp(10))
        if (sel) {
            v.setBackgroundColor(ac)
            v.setTextColor(contrast(ac))
        } else {
            v.setTextColor(tc)
        }
        v.setOnClickListener { f() }
        return v
    }

    private fun addWallpaper() {
        val w = Prefs.wall(this) ?: return
        try {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageURI(Uri.parse(w))
            frame.addView(iv, FrameLayout.LayoutParams(MP, MP))
            val dim = View(this)
            dim.setBackgroundColor(0x99000000.toInt())
            frame.addView(dim, FrameLayout.LayoutParams(MP, MP))
        } catch (e: Exception) {
            toast("Wallpaper could not be opened")
        }
    }

    private fun addAtomic() {
        atomic = null
        if (!Prefs.atomicOn(this) || Prefs.themeMode(this) == "none") return
        val av = AtomicView(this, false)
        atomic = av
        frame.addView(av, FrameLayout.LayoutParams(MP, MP))
    }

    private fun addBorder() {
        if (Prefs.rgbBorder(this)) frame.addView(RgbBorderView(this), FrameLayout.LayoutParams(MP, MP))
    }

    private fun startRgb() {
        ui.removeCallbacks(rgbTick)
        if (rgbTargets.isNotEmpty()) ui.post(rgbTick)
    }

    private fun durStr(ms: Long): String {
        val s = ms / 1000
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss) else String.format("%d:%02d", mm, ss)
    }

    private fun sizeStr(b: Long): String =
        if (b >= 1073741824L) String.format("%.1f GB", b / 1073741824.0)
        else String.format("%.0f MB", b / 1048576.0)

    private fun folderName(p: String): String {
        val s = p.trim('/').split('/').lastOrNull() ?: ""
        return if (s.isEmpty()) "Internal Storage" else s
    }

    private fun loadMedia() {
        val vList = ArrayList<V>()
        val vBase = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val vProj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_ADDED
        )
        try {
            contentResolver.query(vBase, vProj, null, null, null)?.use { c ->
                val idc = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nc = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dc = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sc = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val rc = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val dac = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    vList.add(
                        V(
                            c.getString(nc) ?: "video",
                            ContentUris.withAppendedId(vBase, c.getLong(idc)),
                            c.getLong(dc), c.getLong(sc), c.getString(rc) ?: "",
                            c.getLong(dac)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        videos = sortList(vList)

        val aList = ArrayList<V>()
        val aBase = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val aProj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED
        )
        try {
            contentResolver.query(aBase, aProj, MediaStore.Audio.Media.IS_MUSIC + " != 0", null, null)?.use { c ->
                val idc = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nc = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dc = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sc = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val rc = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val dac = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    aList.add(
                        V(
                            c.getString(nc) ?: "audio",
                            ContentUris.withAppendedId(aBase, c.getLong(idc)),
                            c.getLong(dc), c.getLong(sc), c.getString(rc) ?: "",
                            c.getLong(dac)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        audios = sortList(aList)
    }

    private fun sortList(l: List<V>): List<V> {
        val mode = Prefs.sortMode(this)
        val asc = Prefs.sortAsc(this)
        val sorted = when (mode) {
            1 -> l.sortedBy { it.name.lowercase() }
            2 -> l.sortedBy { it.size }
            3 -> l.sortedBy { it.dur }
            else -> l.sortedBy { it.date }
        }
        return if (asc) sorted else sorted.reversed()
    }

    private fun reloadMedia() {
        if (!hasPerm()) return
        loading = true
        loader.execute {
            loadMedia()
            loading = false
            runOnUiThread {
                if (!isFinishing && !inSettings && ::listHolder.isInitialized) renderList()
            }
        }
    }

    private fun playList(list: List<V>, index: Int) {
        if (index < 0 || index >= list.size) return
        val cur = list[index]
        Prefs.setLastPlayed(this, cur.uri.toString())
        val aud = isAudio(cur.uri)
        val q = list.filter { isAudio(it.uri) == aud }
        val qi = q.indexOfFirst { it.uri == cur.uri }
        val cls: Class<*> = if (aud) AudioActivity::class.java else PlayerActivity::class.java
        val i = Intent(this, cls)
        i.data = cur.uri
        i.putStringArrayListExtra("list", ArrayList(q.map { it.uri.toString() }))
        i.putStringArrayListExtra("names", ArrayList(q.map { it.name }))
        i.putExtra("idx", qi)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
    }

    private fun openNowPlaying() {
        val curUri = Engine.items.getOrNull(Engine.idx)
        if (curUri != null) Prefs.setLastPlayed(this, curUri.toString())
        val cls: Class<*> = if (Engine.isVideo) PlayerActivity::class.java else AudioActivity::class.java
        val i = Intent(this, cls)
        i.data = curUri
        i.putExtra("cont", true)
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(i)
    }

    private fun openFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        startActivityForResult(i, 2)
    }

    private fun pickWall() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        startActivityForResult(i, 3)
    }

    private fun streamDialog() {
        val et = EditText(this)
        et.hint = "http://... or rtsp://..."
        AlertDialog.Builder(this)
            .setTitle("Network Stream URL")
            .setView(et)
            .setPositiveButton("Play") { _, _ ->
                val u = et.text.toString().trim()
                if (u.isNotEmpty()) {
                    Prefs.setLastPlayed(this, u)
                    val i = Intent(this, PlayerActivity::class.java)
                    i.data = Uri.parse(u)
                    startActivity(i)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleSel(u: Uri?) {
        if (u == null) return
        val k = u.toString()
        if (selected.contains(k)) selected.remove(k) else selected.add(k)
        updateSelCount()
    }

    private fun updateSelCount() {
        selCountTv?.text = selected.size.toString() + " selected"
        curAdapter?.notifyDataSetChanged()
    }

    private fun selectAll() {
        for (v in shown) selected.add(v.uri.toString())
        updateSelCount()
    }

    private fun exitSelect() {
        selectMode = false
        selected.clear()
        showHome()
    }

    private fun favSelected() {
        if (selected.isEmpty()) {
            toast("Tick some items first")
            return
        }
        var n = 0
        for (k in selected) {
            if (!Prefs.isFav(this, k)) {
                Prefs.toggleFav(this, k)
                n++
            }
        }
        toast(n.toString() + " item(s) added to Playlist")
        exitSelect()
    }

    private fun confirmDelete() {
        if (selected.isEmpty()) {
            toast("Tick some items first")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage(selected.size.toString() + " file(s) will be deleted permanently.")
            .setPositiveButton("Delete") { _, _ -> doDelete() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doDelete() {
        val uris = selected.map { Uri.parse(it) }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = MediaStore.createDeleteRequest(contentResolver, uris)
                startIntentSenderForResult(pi.intentSender, 9, null, 0, 0, 0)
            } else {
                for (u in uris) contentResolver.delete(u, null, null)
                afterDelete()
            }
        } catch (e: Exception) {
            toast("Delete failed: " + e.message)
        }
    }

    private fun afterDelete() {
        if (Engine.active && Engine.items.isNotEmpty() && selected.contains(Engine.cur().toString())) Engine.stopAll(this)
        selected.clear()
        selectMode = false
        dirty = true
        toast("Deleted")
        showHome()
    }

    private fun sortDialog() {
        val items = arrayOf<CharSequence>(
            "Date: new to old", "Date: old to new",
            "Name: A to Z", "Name: Z to A",
            "Size: big to small", "Size: small to big",
            "Length: long to short", "Length: short to long"
        )
        AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setItems(items) { _, i ->
                Prefs.setSortMode(this, i / 2)
                Prefs.setSortAsc(this, i % 2 == 1)
                dirty = true
                showHome()
            }
            .show()
    }

    private fun makeRow(): Pair<View, H> {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), dp(6), dp(8), dp(6))

        val check = TextView(this)
        check.textSize = 22f
        check.setPadding(dp(2), 0, dp(10), 0)
        check.visibility = View.GONE
        row.addView(check)

        val box = FrameLayout(this)
        val thumb = ImageView(this)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.setBackgroundColor(0xFF1E1E2E.toInt())
        box.addView(thumb, FrameLayout.LayoutParams(MP, MP))

        val badge = TextView(this)
        badge.textSize = 11f
        badge.setTextColor(Color.WHITE)
        badge.setBackgroundColor(0xCC000000.toInt())
        badge.setPadding(dp(6), dp(2), dp(6), dp(2))
        val blp = FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.END)
        blp.setMargins(0, 0, dp(4), dp(4))
        box.addView(badge, blp)

        val rowWidth = if (Prefs.gridView(this)) dp(140) else dp(130)
        val rowHeight = if (Prefs.gridView(this)) dp(180) else dp(74)
        row.addView(box, LinearLayout.LayoutParams(rowWidth, rowHeight))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, 0, 0)
        val title = TextView(this)
        title.textSize = 14f
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        val sub = TextView(this)
        sub.textSize = 12f
        col.addView(title)
        col.addView(sub)
        row.addView(col, LinearLayout.LayoutParams(0, WC, 1f))
        return Pair(row, H(row, check, thumb, badge, title, sub))
    }

    private fun makeAdapter(items: List<Item>, tc: Int, ac: Int): BaseAdapter = object : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: H
            val v: View
            if (convertView == null) {
                val pr = makeRow()
                v = pr.first
                holder = pr.second
                v.tag = holder
            } else {
                v = convertView
                holder = convertView.tag as H
            }
            val item = items[position]
            val isFolder = item.tag.startsWith("F:")
            val u = item.uri
            val lastUri = Prefs.lastPlayed(this@MainActivity)
            val isLastPlayed = !isFolder && u != null && u.toString() == lastUri

            if (isLastPlayed) {
                holder.title.text = "\u25B6 " + item.title
                holder.title.setTextColor(ac)
                holder.title.setTypeface(null, Typeface.BOLD)
                holder.sub.text = "LAST PLAYED \u2022 " + item.sub
                holder.sub.setTextColor(ac)
                val gd = GradientDrawable()
                gd.setColor((ac and 0x00FFFFFF) or 0x26000000)
                gd.setStroke(dp(1), ac)
                gd.cornerRadius = dp(10).toFloat()
                holder.rowRoot.background = gd
            } else {
                holder.title.text = item.title
                holder.title.setTextColor(tc)
                holder.title.setTypeface(null, Typeface.NORMAL)
                holder.sub.text = item.sub
                holder.sub.setTextColor((tc and 0x00FFFFFF) or 0x99000000.toInt())
                holder.rowRoot.background = null
            }

            holder.badge.text = item.badge
            holder.badge.visibility = if (item.badge.isEmpty()) View.GONE else View.VISIBLE

            holder.check.visibility = if (selectMode && !isFolder) View.VISIBLE else View.GONE
            holder.check.setTextColor(ac)
            holder.check.text = if (u != null && selected.contains(u.toString())) "\u2611" else "\u2610"

            holder.thumb.setImageBitmap(null)
            if (u != null) {
                val key = u.toString()
                holder.thumb.tag = key
                val cached = cache.get(key)
                if (cached != null) {
                    holder.thumb.setImageBitmap(cached)
                } else {
                    pool.execute {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                        val b: Bitmap? = try {
                            contentResolver.loadThumbnail(u, Size(256, 144), null)
                        } catch (e: Exception) {
                            null
                        }
                        if (b != null) {
                            cache.put(key, b)
                            runOnUiThread {
                                if (holder.thumb.tag == key) holder.thumb.setImageBitmap(b)
                            }
                        }
                    }
                }
            }
            return v
        }
    }

    private fun refreshMini() {
        if (!::miniBar.isInitialized) return
        if (!Engine.active || inSettings) {
            miniBar.visibility = View.GONE
            return
        }
        miniBar.visibility = View.VISIBLE
        miniTitle.text = (if (Engine.isVideo) "\uD83C\uDFAC " else "\uD83C\uDFB5 ") + Engine.title
        miniPlay.text = if (Engine.isPlaying()) "\u23F8" else "\u25B6"
    }

    private fun buildMini(ac: Int): LinearLayout {
        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(12), dp(4), dp(4), dp(4))
        val g = GradientDrawable()
        g.setColor(0xEE1A222D.toInt())
        g.setStroke(dp(1), ac)
        g.cornerRadius = dp(14).toFloat()
        bar.background = g
        miniTitle = TextView(this)
        miniTitle.setTextColor(Color.WHITE)
        miniTitle.textSize = 13f
        miniTitle.maxLines = 1
        miniTitle.ellipsize = TextUtils.TruncateAt.END
        bar.addView(miniTitle, LinearLayout.LayoutParams(0, WC, 1f))
        bar.addView(sym("\u23EE", Color.WHITE) { Engine.prev(this) })
        miniPlay = sym("\u23F8", Color.WHITE) { Engine.toggle(this) }
        bar.addView(miniPlay)
        bar.addView(sym("\u23ED", Color.WHITE) { Engine.next(this) })
        bar.addView(sym("\u2715", Color.WHITE) { Engine.stopAll(this) })
        bar.setOnClickListener { openNowPlaying() }
        bar.visibility = View.GONE
        return bar
    }

    private fun showHome() {
        inSettings = false
        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)
        val ic = Prefs.iconColor(this)
        val rgbAcc = Prefs.themeMode(this) == "rgb" && !Prefs.hasOverride(this, Prefs.K_ACCENT)
        rgbTargets.clear()
        frame.removeAllViews()
        frame.setBackgroundColor(Prefs.bg(this))
        addWallpaper()
        addAtomic()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(32), dp(12), dp(8))

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        if (selectMode) {
            head.addView(sym("\u2715", ic) { exitSelect() })
            val cnt = TextView(this)
            cnt.text = selected.size.toString() + " selected"
            cnt.textSize = 17f
            cnt.setTextColor(tc)
            selCountTv = cnt
            head.addView(cnt, LinearLayout.LayoutParams(0, WC, 1f))
            head.addView(sym("\u2611", ic) { selectAll() })
            head.addView(sym("\u2B50", ic) { favSelected() })
            head.addView(sym("\uD83C\uDFB5", ic) {
                val vs = videos.filter { selected.contains(it.uri.toString()) }
                Hooks.convert(this, vs.map { it.uri }, vs.map { it.name }) {
                    dirty = true
                    exitSelect()
                }
            })
            head.addView(sym("\uD83D\uDDD1", Color.parseColor("#FF5252")) { confirmDelete() })
        } else {
            selCountTv = null
            val brand = TextView(this)
            brand.text = "\u26A1 RajPlayer Pro"
            brand.textSize = 22f
            brand.setTextColor(ac)
            brand.setTypeface(null, Typeface.BOLD)
            if (rgbAcc) rgbTargets.add { c -> brand.setTextColor(c) }
            head.addView(brand, LinearLayout.LayoutParams(0, WC, 1f))
            head.addView(sym("\uD83D\uDD0D", ic) {
                searchOn = !searchOn
                if (!searchOn) query = ""
                showHome()
            })
            head.addView(sym("\uD83C\uDF10", ic) { streamDialog() })
            head.addView(sym("\uD83D\uDCC2", ic) { openFile() })
            head.addView(sym("\u2699", ic) {
                settingsY = 0
                showSettings()
            })
        }
        root.addView(head)

        val line = View(this)
        line.setBackgroundColor(ac)
        if (rgbAcc) rgbTargets.add { c -> line.setBackgroundColor(c) }
        val llp = LinearLayout.LayoutParams(MP, dp(2))
        llp.setMargins(0, dp(6), 0, 0)
        root.addView(line, llp)

        val navRow = LinearLayout(this)
        navRow.gravity = Gravity.CENTER_VERTICAL
        navRow.setPadding(0, dp(8), 0, dp(8))

        val tabs = LinearLayout(this)
        tabs.addView(tabBtn("Videos", tab == 0, ac, tc) {
            tab = 0
            openFolder = null
            showHome()
        }, LinearLayout.LayoutParams(0, WC, 1f))
        tabs.addView(tabBtn("Folders", tab == 1, ac, tc) {
            tab = 1
            openFolder = null
            showHome()
        }, LinearLayout.LayoutParams(0, WC, 1f))
        tabs.addView(tabBtn("Music", tab == 2, ac, tc) {
            tab = 2
            openFolder = null
            showHome()
        }, LinearLayout.LayoutParams(0, WC, 1f))
        tabs.addView(tabBtn("Playlist", tab == 3, ac, tc) {
            tab = 3
            openFolder = null
            showHome()
        }, LinearLayout.LayoutParams(0, WC, 1f))
        navRow.addView(tabs, LinearLayout.LayoutParams(0, WC, 1f))

        navRow.addView(sym("\u21C5", ic) { sortDialog() })
        navRow.addView(sym(if (Prefs.gridView(this)) "\u2637" else "\u2638", ic) {
            Prefs.setGridView(this, !Prefs.gridView(this))
            showHome()
        })
        root.addView(navRow)

        if (searchOn) {
            val se = EditText(this)
            se.hint = "Search media..."
            se.setTextColor(tc)
            se.setHintTextColor((tc and 0x00FFFFFF) or 0x77000000)
            se.setText(query)
            se.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    renderList()
                }

                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
            root.addView(se)
        }

        listHolder = FrameLayout(this)
        root.addView(listHolder, LinearLayout.LayoutParams(MP, 0, 1f))

        miniBar = buildMini(ac)
        val mlp = LinearLayout.LayoutParams(MP, WC)
        mlp.setMargins(0, dp(6), 0, 0)
        root.addView(miniBar, mlp)

        renderList()
        refreshMini()
        frame.addView(root, FrameLayout.LayoutParams(MP, MP))
        addBorder()
        startRgb()

        if (hasPerm() && dirty) {
            dirty = false
            reloadMedia()
        }
    }

    private fun renderList() {
        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)
        listHolder.removeAllViews()

        if (!hasPerm()) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(16), dp(24), dp(16), 0)
            col.addView(label("Storage permissions are required.", 16f, tc))
            col.addView(button("Grant Permission", ac) { reqPerms() })
            col.addView(button("Open App Settings", ac) { openAppSettings() })
            col.addView(label("Settings > Permissions: allow 'Music and audio' and 'Photos and videos', then come back.", 13f, tc))
            listHolder.addView(col)
            return
        }

        val items = ArrayList<Item>()
        val of = openFolder
        val q = query.trim().lowercase()
        val activeList = if (tab == 2) audios else videos

        if (q.isNotEmpty()) {
            shown = (videos + audios).filter { it.name.lowercase().contains(q) }
            for (v in shown) {
                items.add(Item(v.name, folderName(v.folder) + " \u2022 " + sizeStr(v.size), v.uri, durStr(v.dur), if (isAudio(v.uri)) "\uD83C\uDFB5" else "V"))
            }
        } else if (tab == 3) {
            val favSet = Prefs.favs(this)
            shown = videos.filter { favSet.contains(it.uri.toString()) } + audios.filter { favSet.contains(it.uri.toString()) }
            for (v in shown) {
                items.add(Item(v.name, folderName(v.folder) + " \u2022 " + sizeStr(v.size), v.uri, durStr(v.dur), "\u2B50"))
            }
        } else if (tab == 2) {
            shown = audios
            for (v in shown) {
                items.add(Item(v.name, folderName(v.folder) + " \u2022 " + sizeStr(v.size), v.uri, durStr(v.dur), "\uD83C\uDFB5"))
            }
        } else if (tab == 0 || of != null) {
            shown = if (of != null) activeList.filter { it.folder == of } else activeList
            for (v in shown) {
                items.add(Item(v.name, folderName(v.folder) + " \u2022 " + sizeStr(v.size), v.uri, durStr(v.dur), "V"))
            }
        } else {
            val groups = videos.groupBy { it.folder }
            val keys = groups.keys.sortedBy { folderName(it).lowercase() }
            for (k in keys) {
                val l = groups[k] ?: continue
                items.add(Item(folderName(k), l.size.toString() + " videos", l[0].uri, l.size.toString(), "F:" + k))
            }
        }

        if (items.isEmpty()) {
            val emptyMsg = when {
                loading -> "Loading..."
                tab == 3 -> "Playlist is empty. Long-press an item, tick it, then tap the star."
                else -> "No items found."
            }
            listHolder.addView(label(emptyMsg, 15f, tc))
            return
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        if (of != null && q.isEmpty() && tab != 2 && tab != 3) col.addView(label("\uD83D\uDCC1 " + folderName(of), 15f, tc))

        val lv = ListView(this)
        lv.divider = null
        val ad = makeAdapter(items, tc, ac)
        curAdapter = ad
        lv.adapter = ad
        lv.setOnItemClickListener { _, _, i, _ ->
            val chosen = items[i]
            if (chosen.tag.startsWith("F:")) {
                if (!selectMode) {
                    openFolder = chosen.tag.substring(2)
                    showHome()
                }
            } else if (selectMode) {
                toggleSel(chosen.uri)
            } else {
                playList(shown, shown.indexOfFirst { it.uri == chosen.uri })
            }
        }
        lv.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, i, _ ->
            val chosen = items[i]
            if (!chosen.tag.startsWith("F:") && chosen.uri != null) {
                if (!selectMode) {
                    selectMode = true
                    selected.add(chosen.uri.toString())
                    showHome()
                } else {
                    toggleSel(chosen.uri)
                }
            }
            true
        }
        col.addView(lv, LinearLayout.LayoutParams(MP, 0, 1f))
        listHolder.addView(col, FrameLayout.LayoutParams(MP, MP))
    }

    private fun refreshSettings() {
        settingsY = settingsSv?.scrollY ?: 0
        showSettings()
    }

    private fun colorPicker(root: LinearLayout, title: String, key: String) {
        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)

        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(0, dp(12), 0, dp(2))
        val sw = View(this)
        val g = GradientDrawable()
        g.setColor(Prefs.color(this, key))
        g.setStroke(dp(1), 0xFF888888.toInt())
        g.cornerRadius = dp(6).toFloat()
        sw.background = g
        head.addView(sw, LinearLayout.LayoutParams(dp(26), dp(26)))
        val state = if (Prefs.hasOverride(this, key)) "custom" else "theme"
        val t = TextView(this)
        t.text = "  " + title + "  (" + state + ")"
        t.textSize = 14f
        t.setTextColor(tc)
        head.addView(t)
        root.addView(head)

        val hs = HorizontalScrollView(this)
        val row = LinearLayout(this)
        for (hex in palette) {
            val c = Color.parseColor(hex)
            val b = View(this)
            val gd = GradientDrawable()
            gd.setColor(c)
            gd.setStroke(dp(1), 0xFF555555.toInt())
            gd.cornerRadius = dp(8).toFloat()
            b.background = gd
            val lp = LinearLayout.LayoutParams(dp(34), dp(34))
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            b.layoutParams = lp
            b.setOnClickListener {
                Prefs.setColor(this, key, c)
                refreshSettings()
            }
            row.addView(b)
        }
        hs.addView(row)
        root.addView(hs)

        val er = LinearLayout(this)
        er.gravity = Gravity.CENTER_VERTICAL
        val et = EditText(this)
        et.hint = "#RRGGBB or #AARRGGBB"
        et.setTextColor(tc)
        et.setHintTextColor((tc and 0x00FFFFFF) or 0x77000000)
        et.textSize = 13f
        et.setSingleLine(true)
        er.addView(et, LinearLayout.LayoutParams(0, WC, 1f))
        er.addView(button("Apply", ac) {
            var s = et.text.toString().trim()
            if (!s.startsWith("#")) s = "#" + s
            try {
                Prefs.setColor(this, key, Color.parseColor(s))
                refreshSettings()
            } catch (e: Exception) {
                toast("Invalid hex code")
            }
        })
        er.addView(button("Reset", ac) {
            Prefs.clearColor(this, key)
            refreshSettings()
        })
        root.addView(er)
    }

    private fun showSettings() {
        inSettings = true
        val tc = Prefs.text(this)
        val ac = Prefs.accent(this)
        rgbTargets.clear()
        frame.removeAllViews()
        frame.setBackgroundColor(Prefs.bg(this))
        addWallpaper()
        addAtomic()

        val sv = ScrollView(this)
        settingsSv = sv
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(40), dp(16), dp(32))

        root.addView(label("\u2699\uFE0F Ultra Settings", 24f, tc))
        root.addView(button("\u2190 Back", ac) {
            settingsY = 0
            showHome()
        })

        // Theme Presets
        root.addView(label("Theme Presets", 16f, tc))
        val cur = Prefs.themeMode(this)
        val presetRow = HorizontalScrollView(this)
        val pr = LinearLayout(this)
        for (i in Theme.MODES.indices) {
            val m = Theme.MODES[i]
            val nm = Theme.NAMES[i]
            pr.addView(button((if (m == cur) "\u2713 " else "") + nm, ac) {
                Prefs.setThemeMode(this, m, m != "none")
                refreshSettings()
            })
        }
        presetRow.addView(pr)
        root.addView(presetRow)

        // Animation & Margin Controls (Up to 6.0x)
        root.addView(label("Diamond Bonding Animation & Speed (0.5x - 6.0x)", 16f, tc))
        root.addView(button("App Background Atomic Animation: " + (if (Prefs.atomicOn(this)) "ON" else "OFF"), ac) {
            Prefs.setAtomicOn(this, !Prefs.atomicOn(this))
            refreshSettings()
        })
        root.addView(button("Video Player Black Margin Animation: " + (if (Prefs.playerMarginAnim(this)) "ON" else "OFF"), ac) {
            Prefs.setPlayerMarginAnim(this, !Prefs.playerMarginAnim(this))
            refreshSettings()
        })
        root.addView(button("RGB Glowing Border: " + (if (Prefs.rgbBorder(this)) "ON" else "OFF"), ac) {
            Prefs.setRgbBorder(this, !Prefs.rgbBorder(this))
            refreshSettings()
        })

        val spTv = label("Animation Speed: " + String.format("%.1fx", Prefs.atomSpeed(this)), 14f, tc)
        root.addView(spTv)
        val spSb = SeekBar(this)
        spSb.max = 110 // 0.5x + (110 * 0.05) = 6.0x
        spSb.progress = Math.round((Prefs.atomSpeed(this) - 0.5f) / 0.05f).coerceIn(0, 110)
        spSb.progressTintList = ColorStateList.valueOf(ac)
        spSb.thumbTintList = ColorStateList.valueOf(ac)
        spSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = 0.5f + p * 0.05f
                spTv.text = "Animation Speed: " + String.format("%.1fx", v)
                if (fromUser) atomic?.setSpeedMultiplier(v)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}

            override fun onStopTrackingTouch(s: SeekBar?) {
                Prefs.setAtomSpeed(this@MainActivity, 0.5f + (s?.progress ?: 10) * 0.05f)
            }
        })
        root.addView(spSb)

        // Quick Speed Presets
        val qRow = LinearLayout(this)
        for (sp in floatArrayOf(1f, 2f, 4f, 6f)) {
            qRow.addView(button(String.format("%.0fx", sp), ac) {
                Prefs.setAtomSpeed(this, sp)
                refreshSettings()
            }, LinearLayout.LayoutParams(0, WC, 1f))
        }
        root.addView(qRow)

        // Playback
        root.addView(label("Playback", 16f, tc))
        root.addView(button("Background Audio: " + (if (Prefs.bgAudio(this)) "ON" else "OFF"), ac) {
            Prefs.setBgAudio(this, !Prefs.bgAudio(this))
            Engine.bgAudio = Prefs.bgAudio(this)
            refreshSettings()
        })
        root.addView(button("Background Activity (Battery): " + (if (Power.isIgnoring(this)) "ALLOWED \u2705" else "Tap to allow"), ac) {
            Power.request(this)
        })
        val seekSecs = intArrayOf(5, 10, 15, 20, 30)
        val seekNames = arrayOf<CharSequence>("5 seconds", "10 seconds", "15 seconds", "20 seconds", "30 seconds")
        root.addView(button("Double-tap Seek: " + Prefs.seekSec(this) + "s", ac) {
            AlertDialog.Builder(this)
                .setTitle("Seek duration")
                .setItems(seekNames) { _, i ->
                    Prefs.setSeekSec(this, seekSecs[i])
                    refreshSettings()
                }
                .show()
        })

        // Granular Color Pickers
        root.addView(label("Granular Colours (Palette or Custom Hex)", 16f, tc))
        colorPicker(root, "Background Colour", Prefs.K_BG)
        colorPicker(root, "Accent Colour", Prefs.K_ACCENT)
        colorPicker(root, "General Text Colour", Prefs.K_TEXT)
        colorPicker(root, "Video Title Bar Colour", Prefs.K_VBAR)
        colorPicker(root, "Video Title Text Colour", Prefs.K_VTEXT)
        colorPicker(root, "Audio Title Bar Colour", Prefs.K_ABAR)
        colorPicker(root, "Audio Title Text Colour", Prefs.K_ATEXT)
        colorPicker(root, "General Icon Colour", Prefs.K_ICON)
        root.addView(button("Reset all colours to theme", ac) {
            Prefs.resetColors(this)
            refreshSettings()
        })

        // Wallpaper
        root.addView(label("Wallpaper", 16f, tc))
        root.addView(button("Choose Wallpaper", ac) { pickWall() })
        root.addView(button("Remove Wallpaper", ac) {
            Prefs.setWall(this, null)
            refreshSettings()
        })

        sv.addView(root)
        frame.addView(sv, FrameLayout.LayoutParams(MP, MP))
        addBorder()
        startRgb()
        sv.post { sv.scrollTo(0, settingsY) }
    }
}
EOF

# ==================== 5. AudioActivity.kt ====================
cat > "$PK/AudioActivity.kt" <<'EOF'
package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.Executors

class AudioActivity : Activity() {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var curTv: TextView
    private lateinit var durTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: TextView
    private lateinit var shufBtn: TextView
    private lateinit var repBtn: TextView
    private lateinit var artImg: ImageView
    private lateinit var artNote: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private var userSeeking = false
    private var artKey = ""
    private val speeds = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f, 6f)
    private var speedIdx = 1
    private val rgbTargets = ArrayList<(Int) -> Unit>()

    private val lis = object : Engine.Listener {
        override fun onItemChanged() {
            runOnUiThread { onItem() }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            update()
            ui.postDelayed(this, 500)
        }
    }

    private val rgbTick = object : Runnable {
        override fun run() {
            if (rgbTargets.isNotEmpty()) {
                val col = Theme.rgbNow(0f)
                for (t in rgbTargets) t(col)
                ui.postDelayed(this, 70)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmt(ms: Long): String {
        val s = if (ms < 0) 0L else ms / 1000
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss) else String.format("%d:%02d", mm, ss)
    }

    private fun contrast(c: Int) = if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE

    private fun ib(sym: String, size: Float, color: Int, f: (TextView) -> Unit): TextView {
        val t = TextView(this)
        t.text = sym
        t.textSize = size
        t.setTextColor(color)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(10), dp(8), dp(10), dp(8))
        t.setOnClickListener { f(t) }
        return t
    }

    @Suppress("DEPRECATION")
    private fun extractIncomingUri(): Uri? {
        if (intent?.action == Intent.ACTION_SEND) {
            val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (stream != null) return stream
            val cd = intent.clipData
            if (cd != null && cd.itemCount > 0) return cd.getItemAt(0).uri
        }
        return intent?.data
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cont = intent.getBooleanExtra("cont", false) && Engine.active && !Engine.isVideo
        var uris: List<Uri> = emptyList()
        var names: List<String> = emptyList()
        var index = 0
        if (!cont) {
            val u0 = extractIncomingUri()
            if (u0 == null) {
                finish()
                return
            }
            Prefs.setLastPlayed(this, u0.toString())
            val l = intent.getStringArrayListExtra("list")
            uris = if (l != null && l.isNotEmpty()) l.map { Uri.parse(it) } else listOf(u0)
            val nm = intent.getStringArrayListExtra("names")
            names = if (nm != null && nm.size == uris.size) nm.toList() else emptyList()
            index = intent.getIntExtra("idx", 0)
        }

        val tc = Prefs.audioTextColor(this)
        val ic = Prefs.iconColor(this)
        val ac = Prefs.accent(this)
        val bar = Prefs.audioBarColor(this)
        val rgb = Prefs.themeMode(this) == "rgb"
        val rgbAcc = rgb && !Prefs.hasOverride(this, Prefs.K_ACCENT)
        val rgbTxt = rgb && !Prefs.hasOverride(this, Prefs.K_ATEXT)

        val root = FrameLayout(this)
        root.setBackgroundColor(Prefs.bg(this))
        val w = Prefs.wall(this)
        if (w != null) {
            try {
                val iv = ImageView(this)
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setImageURI(Uri.parse(w))
                root.addView(iv, FrameLayout.LayoutParams(MP, MP))
                val dim = View(this)
                dim.setBackgroundColor(0xBB000000.toInt())
                root.addView(dim, FrameLayout.LayoutParams(MP, MP))
            } catch (e: Exception) {
            }
        }

        var atomic: AtomicView? = null
        if (Prefs.atomicOn(this) && Prefs.themeMode(this) != "none") {
            val av = AtomicView(this, false)
            atomic = av
            root.addView(av, FrameLayout.LayoutParams(MP, MP))
        }

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(24), dp(36), dp(24), dp(16))

        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(bar)
        top.setPadding(dp(4), dp(2), dp(4), dp(2))
        top.addView(ib("\u2190", 24f, ic) { finish() })
        val hdr = TextView(this)
        hdr.text = "NOW PLAYING"
        hdr.textSize = 13f
        hdr.setTextColor(tc)
        hdr.gravity = Gravity.CENTER
        top.addView(hdr, LinearLayout.LayoutParams(0, WC, 1f))
        top.addView(ib("\u22EF", 24f, ic) { menu() })
        top.addView(ib("\u2715", 20f, ic) {
            Engine.stopAll(this)
            finish()
        })
        col.addView(top, LinearLayout.LayoutParams(MP, WC))

        val artSize = Math.min(resources.displayMetrics.widthPixels - dp(64), dp(320))
        val artBox = FrameLayout(this)
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ac, 0xFF000000.toInt()))
        artBox.background = g
        artBox.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dp(24).toFloat())
            }
        }
        artBox.clipToOutline = true
        artNote = TextView(this)
        artNote.text = "\uD83C\uDFB5"
        artNote.textSize = 84f
        artNote.gravity = Gravity.CENTER
        artBox.addView(artNote, FrameLayout.LayoutParams(MP, MP))
        artImg = ImageView(this)
        artImg.scaleType = ImageView.ScaleType.CENTER_CROP
        artBox.addView(artImg, FrameLayout.LayoutParams(MP, MP))
        val alp = LinearLayout.LayoutParams(artSize, artSize)
        alp.setMargins(0, dp(20), 0, dp(20))
        col.addView(artBox, alp)
        atomic?.excludeView(artBox)

        titleTv = TextView(this)
        titleTv.textSize = 20f
        titleTv.setTextColor(tc)
        titleTv.gravity = Gravity.CENTER
        titleTv.maxLines = 2
        titleTv.ellipsize = TextUtils.TruncateAt.END
        titleTv.setTypeface(null, Typeface.BOLD)
        col.addView(titleTv, LinearLayout.LayoutParams(MP, WC))
        subTv = TextView(this)
        subTv.textSize = 13f
        subTv.setTextColor((tc and 0x00FFFFFF) or 0x99000000.toInt())
        subTv.gravity = Gravity.CENTER
        subTv.setPadding(0, dp(4), 0, dp(12))
        col.addView(subTv, LinearLayout.LayoutParams(MP, WC))

        val seekRow = LinearLayout(this)
        seekRow.gravity = Gravity.CENTER_VERTICAL
        curTv = TextView(this)
        curTv.setTextColor(tc)
        curTv.textSize = 12f
        curTv.text = "0:00"
        durTv = TextView(this)
        durTv.setTextColor(tc)
        durTv.textSize = 12f
        durTv.text = "0:00"
        seek = SeekBar(this)
        seek.progressTintList = ColorStateList.valueOf(ac)
        seek.thumbTintList = ColorStateList.valueOf(ac)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) curTv.text = fmt(p.toLong())
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                Engine.seekTo((s?.progress ?: 0).toLong())
                userSeeking = false
            }
        })
        seekRow.addView(curTv)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, WC, 1f))
        seekRow.addView(durTv)
        col.addView(seekRow, LinearLayout.LayoutParams(MP, WC))

        val tr = LinearLayout(this)
        tr.gravity = Gravity.CENTER
        shufBtn = ib("\uD83D\uDD00", 22f, ic) {
            Prefs.setShuffle(this, !Prefs.shuffle(this))
            refreshModes()
        }
        repBtn = ib("\uD83D\uDD01", 22f, ic) {
            Prefs.setRepeat(this, (Prefs.repeat(this) + 1) % 3)
            refreshModes()
        }
        tr.addView(shufBtn)
        tr.addView(ib("\u23EE", 30f, ic) { Engine.prev(this) })
        playBtn = ib("\u23F8", 30f, contrast(ac)) { Engine.toggle(this) }
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(ac)
        playBtn.background = circle
        val plp = LinearLayout.LayoutParams(dp(72), dp(72))
        plp.setMargins(dp(12), 0, dp(12), 0)
        tr.addView(playBtn, plp)
        tr.addView(ib("\u23ED", 30f, ic) { Engine.next(this) })
        tr.addView(repBtn)
        col.addView(tr, LinearLayout.LayoutParams(MP, WC))

        val ex = LinearLayout(this)
        ex.gravity = Gravity.CENTER
        ex.setPadding(0, dp(12), 0, 0)
        ex.addView(ib("1.0x", 14f, ic) { b ->
            speedIdx = (speedIdx + 1) % speeds.size
            Engine.mp?.setRate(speeds[speedIdx])
            b.text = speeds[speedIdx].toString() + "x"
        })
        ex.addView(ib("EQ", 14f, ic) { Dialogs.eq(this) })
        ex.addView(ib("Boost", 14f, ic) { Dialogs.boost(this) })
        ex.addView(ib("Sleep", 14f, ic) { Dialogs.sleep(this) })
        ex.addView(ib("Queue", 14f, ic) { queueDialog() })
        col.addView(ex, LinearLayout.LayoutParams(MP, WC))

        root.addView(col, FrameLayout.LayoutParams(MP, MP))
        if (Prefs.rgbBorder(this)) root.addView(RgbBorderView(this), FrameLayout.LayoutParams(MP, MP))
        setContentView(root)

        if (rgbAcc) {
            rgbTargets.add { c ->
                seek.progressTintList = ColorStateList.valueOf(c)
                seek.thumbTintList = ColorStateList.valueOf(c)
            }
            rgbTargets.add { c -> g.setColors(intArrayOf(c, 0xFF000000.toInt())) }
        }
        if (rgbTxt) rgbTargets.add { c -> titleTv.setTextColor(c) }

        Engine.listener = lis
        if (!cont) Engine.setQueue(this, uris, names, index, false)
        onItem()
        refreshModes()
    }

    private fun refreshModes() {
        shufBtn.alpha = if (Prefs.shuffle(this)) 1f else 0.35f
        val r = Prefs.repeat(this)
        repBtn.text = if (r == 2) "\uD83D\uDD02" else "\uD83D\uDD01"
        repBtn.alpha = if (r == 0) 0.35f else 1f
    }

    private fun onItem() {
        if (!Engine.active) {
            finish()
            return
        }
        val curUri = Engine.items.getOrNull(Engine.idx)
        if (curUri != null) Prefs.setLastPlayed(this, curUri.toString())
        titleTv.text = Engine.title
        subTv.text = (Engine.idx + 1).toString() + " / " + Engine.items.size
        loadArt()
        update()
    }

    private fun loadArt() {
        val u = Engine.items.getOrNull(Engine.idx) ?: return
        val key = u.toString()
        if (key == artKey) return
        artKey = key
        artImg.setImageBitmap(null)
        artNote.visibility = View.VISIBLE
        pool.execute {
            val b: Bitmap? = try {
                contentResolver.loadThumbnail(u, Size(600, 600), null)
            } catch (e: Exception) {
                null
            }
            if (b != null) {
                runOnUiThread {
                    if (artKey == key) {
                        artImg.setImageBitmap(b)
                        artNote.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun update() {
        val p = Engine.mp ?: return
        val len = p.length
        val t = p.time
        if (len > 0) {
            if (!userSeeking) {
                seek.max = len.toInt()
                seek.progress = t.toInt()
            }
            durTv.text = fmt(len)
        }
        if (!userSeeking) curTv.text = fmt(t)
        playBtn.text = if (p.isPlaying) "\u23F8" else "\u25B6"
    }

    private fun menu() {
        val opts = arrayOf<CharSequence>(
            "\uD83C\uDF9A\uFE0F Equalizer",
            "\uD83D\uDD0A Audio Boost",
            "\u23F1\uFE0F Sleep Timer",
            "\uD83D\uDCC3 Songs Queue",
            "\uD83D\uDD0B Allow Background (Battery)",
            "\u23F9 Stop & Close"
        )
        AlertDialog.Builder(this)
            .setItems(opts) { _, i ->
                when (i) {
                    0 -> Dialogs.eq(this)
                    1 -> Dialogs.boost(this)
                    2 -> Dialogs.sleep(this)
                    3 -> queueDialog()
                    4 -> Power.request(this)
                    5 -> {
                        Engine.stopAll(this)
                        finish()
                    }
                }
            }
            .show()
    }

    private fun queueDialog() {
        val n = Engine.items.size
        val last = Prefs.lastPlayed(this)
        val arr = Array<CharSequence>(n) { k ->
            val uStr = Engine.items[k].toString()
            val nm = Engine.names.getOrNull(k) ?: Engine.items[k].lastPathSegment ?: "Track"
            val prefix = if (k == Engine.idx || uStr == last) "\u25B6 " else ""
            prefix + (k + 1) + ". " + nm
        }
        AlertDialog.Builder(this)
            .setTitle("Songs Queue")
            .setItems(arr) { _, i -> Engine.loadAt(this, i) }
            .show()
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
        ui.removeCallbacks(rgbTick)
        if (rgbTargets.isNotEmpty()) ui.post(rgbTick)
        if (Engine.active) onItem()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        ui.removeCallbacks(rgbTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        if (Engine.listener === lis) Engine.listener = null
    }
}
EOF

# ==================== 6. PlayerActivity.kt ====================
cat > "$PK/PlayerActivity.kt" <<'EOF'
package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Rational
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.videolan.libvlc.interfaces.IVLCVout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PlayerActivity : Activity(), SurfaceHolder.Callback, IVLCVout.OnNewVideoLayoutListener {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var root: FrameLayout
    private lateinit var videoContainer: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var marginAtomic: AtomicView
    private lateinit var uiOverlay: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var lockBtn: TextView
    private lateinit var centerToast: TextView
    private lateinit var titleTv: TextView
    private lateinit var curTv: TextView
    private lateinit var durTv: TextView
    private lateinit var seek: SeekBar
    private lateinit var playBtn: TextView
    private lateinit var ratioBtn: TextView
    private lateinit var speedBtn: TextView
    private lateinit var marginBtn: TextView

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var am: AudioManager

    private var videoW = 0
    private var videoH = 0
    private var attached = false
    private var controlsVisible = true
    private var locked = false
    private var userSeeking = false

    // Aspect Ratio & Sizing Modes:
    // 0 = Native Default Fit, 1 = Fill Screen, 2 = 16:9, 3 = 9:16, 4 = 4:3, 5 = Small 75% (shows margin animation), 6 = Mini 55% (shows margin animation)
    private var ratioMode = 0
    private val ratioNames = arrayOf("Native", "Fill", "16:9", "9:16", "4:3", "Small 75%", "Mini 55%")

    // Playback Speeds up to 6.0x
    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f, 6.0f)
    private var speedIdx = 2

    // Zoom & Touch Gesture Isolation State
    private lateinit var scaleDetector: ScaleGestureDetector
    private var zoomScale = 1.0f
    private var isZooming = false
    private var zoomCooldownUntil = 0L
    private var touchMode = 0 // 0 = none, 1 = seek, 2 = volume, 3 = brightness
    private var downX = 0f
    private var downY = 0f
    private var startVol = 0
    private var maxVol = 15
    private var startBright = 0.5f
    private var startSeekPos = 0L
    private var lastTapTime = 0L
    private var lastTapX = 0f

    private val hideControlsRun = Runnable { hideControls() }
    private val hideToastRun = Runnable { centerToast.visibility = View.GONE }

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 400)
        }
    }

    private val lis = object : Engine.Listener {
        override fun onItemChanged() {
            runOnUiThread { onTrackLoaded() }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmt(ms: Long): String {
        val s = if (ms < 0) 0L else ms / 1000
        val hh = s / 3600
        val mm = (s % 3600) / 60
        val ss = s % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss) else String.format("%d:%02d", mm, ss)
    }

    private fun contrast(c: Int) = if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE

    private fun ib(txt: String, size: Float, color: Int, fn: (TextView) -> Unit): TextView {
        val t = TextView(this)
        t.text = txt
        t.textSize = size
        t.setTextColor(color)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(10), dp(6), dp(10), dp(6))
        t.setOnClickListener {
            resetHideTimer()
            fn(t)
        }
        return t
    }

    @Suppress("DEPRECATION")
    private fun extractIncomingUri(): Uri? {
        if (intent?.action == Intent.ACTION_SEND) {
            val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (stream != null) return stream
            val cd = intent.clipData
            if (cd != null && cd.itemCount > 0) return cd.getItemAt(0).uri
        }
        return intent?.data
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Default unlocked user orientation so 16:9 or 9:16 opens without forced sensor rotation bugs
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVol = max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))

        val cont = intent.getBooleanExtra("cont", false) && Engine.active && Engine.isVideo
        var uris: List<Uri> = emptyList()
        var names: List<String> = emptyList()
        var index = 0

        if (!cont) {
            val u0 = extractIncomingUri()
            if (u0 == null) {
                finish()
                return
            }
            Prefs.setLastPlayed(this, u0.toString())
            val l = intent.getStringArrayListExtra("list")
            uris = if (l != null && l.isNotEmpty()) l.map { Uri.parse(it) } else listOf(u0)
            val nm = intent.getStringArrayListExtra("names")
            names = if (nm != null && nm.size == uris.size) nm.toList() else emptyList()
            index = intent.getIntExtra("idx", 0)
        }

        val ic = Prefs.iconColor(this)
        val ac = Prefs.accent(this)
        val vBar = Prefs.videoBarColor(this)
        val vTxt = Prefs.videoTextColor(this)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // 1. Margin Atomic Animation View (Clips out the active video surface so it only renders in black margins)
        marginAtomic = AtomicView(this, isVideoMarginMode = true)
        root.addView(marginAtomic, FrameLayout.LayoutParams(MP, MP))

        // 2. Video Container & SurfaceView
        videoContainer = FrameLayout(this)
        surface = SurfaceView(this)
        surface.holder.addCallback(this)
        videoContainer.addView(surface, FrameLayout.LayoutParams(MP, MP, Gravity.CENTER))
        root.addView(videoContainer, FrameLayout.LayoutParams(MP, MP, Gravity.CENTER))

        // Exclude actual video SurfaceView from atomic animation so animation only draws in the blank black margins
        marginAtomic.excludeView(surface)

        // 3. Touch Gesture Layer (Pinch-to-Zoom strictly isolated from Volume / Brightness / Seek)
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isZooming = true
                touchMode = 0
                zoomCooldownUntil = SystemClock.uptimeMillis() + 250L
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                isZooming = true
                zoomCooldownUntil = SystemClock.uptimeMillis() + 250L
                zoomScale = (zoomScale * detector.scaleFactor).coerceIn(0.5f, 3.0f)
                surface.scaleX = zoomScale
                surface.scaleY = zoomScale
                showCenterToast("Zoom: " + (zoomScale * 100).roundToInt() + "%")
                marginAtomic.invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isZooming = false
                zoomCooldownUntil = SystemClock.uptimeMillis() + 250L
            }
        })

        val touchLayer = View(this)
        touchLayer.setOnTouchListener { _, ev -> handleTouch(ev) }
        root.addView(touchLayer, FrameLayout.LayoutParams(MP, MP))

        // 4. UI Controls Overlay
        uiOverlay = FrameLayout(this)

        // Top Bar
        topBar = LinearLayout(this)
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setBackgroundColor(vBar)
        topBar.setPadding(dp(8), dp(20), dp(8), dp(8))
        topBar.addView(ib("\u2190", 22f, ic) { finish() })

        titleTv = TextView(this)
        titleTv.textSize = 15f
        titleTv.setTextColor(vTxt)
        titleTv.setTypeface(null, Typeface.BOLD)
        titleTv.maxLines = 1
        titleTv.ellipsize = TextUtils.TruncateAt.END
        topBar.addView(titleTv, LinearLayout.LayoutParams(0, WC, 1f))

        topBar.addView(ib("\uD83D\uDD16", 18f, ic) { bookmarksDialog() })
        topBar.addView(ib("\uD83D\uDCF1", 18f, ic) { toggleOrientation() })
        if (Build.VERSION.SDK_INT >= 26) {
            topBar.addView(ib("PiP", 13f, ic) { enterPip() })
        }
        uiOverlay.addView(topBar, FrameLayout.LayoutParams(MP, WC, Gravity.TOP))

        // Lock Button
        lockBtn = ib("\uD83D\uDD13", 22f, ic) { toggleLock() }
        val lockLp = FrameLayout.LayoutParams(WC, WC, Gravity.CENTER_VERTICAL or Gravity.START)
        lockLp.setMargins(dp(14), 0, 0, 0)
        uiOverlay.addView(lockBtn, lockLp)

        // Bottom Bar
        bottomBar = LinearLayout(this)
        bottomBar.orientation = LinearLayout.VERTICAL
        bottomBar.setBackgroundColor(vBar)
        bottomBar.setPadding(dp(12), dp(8), dp(12), dp(16))

        // Seek Row
        val seekRow = LinearLayout(this)
        seekRow.gravity = Gravity.CENTER_VERTICAL
        curTv = TextView(this)
        curTv.setTextColor(vTxt)
        curTv.textSize = 12f
        curTv.text = "0:00"
        durTv = TextView(this)
        durTv.setTextColor(vTxt)
        durTv.textSize = 12f
        durTv.text = "0:00"
        seek = SeekBar(this)
        seek.progressTintList = ColorStateList.valueOf(ac)
        seek.thumbTintList = ColorStateList.valueOf(ac)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    resetHideTimer()
                    curTv.text = fmt(p.toLong())
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                userSeeking = true
                resetHideTimer()
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                Engine.seekTo((s?.progress ?: 0).toLong())
                userSeeking = false
                resetHideTimer()
            }
        })
        seekRow.addView(curTv)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, WC, 1f))
        seekRow.addView(durTv)
        bottomBar.addView(seekRow, LinearLayout.LayoutParams(MP, WC))

        // Main Transport Row
        val tr = LinearLayout(this)
        tr.gravity = Gravity.CENTER
        val seekDelta = Prefs.seekSec(this)
        tr.addView(ib("\u23EE", 24f, ic) { Engine.prev(this) })
        tr.addView(ib("-" + seekDelta + "s", 14f, ic) { skipBy(-seekDelta * 1000L) })

        playBtn = ib("\u23F8", 26f, contrast(ac)) {
            Engine.toggle(this)
            updateProgress()
        }
        val playCircle = GradientDrawable()
        playCircle.shape = GradientDrawable.OVAL
        playCircle.setColor(ac)
        playBtn.background = playCircle
        val plp = LinearLayout.LayoutParams(dp(58), dp(58))
        plp.setMargins(dp(16), dp(4), dp(16), dp(4))
        tr.addView(playBtn, plp)

        tr.addView(ib("+" + seekDelta + "s", 14f, ic) { skipBy(seekDelta * 1000L) })
        tr.addView(ib("\u23ED", 24f, ic) { Engine.next(this) })
        bottomBar.addView(tr, LinearLayout.LayoutParams(MP, WC))

        // Bottom Extra Options Row (Speed up to 6x, Aspect Ratio, Margin Animation Toggle, Audio/Sub)
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val ex = LinearLayout(this)
        ex.gravity = Gravity.CENTER_VERTICAL

        speedBtn = ib("1.0x", 13f, ic) { speedDialog() }
        ex.addView(speedBtn)

        ratioBtn = ib("Ratio: Native", 13f, ic) { cycleRatio() }
        ex.addView(ratioBtn)

        marginBtn = ib(
            if (Prefs.playerMarginAnim(this)) "\u2728 Margin: ON" else "\u2728 Margin: OFF",
            13f, ic
        ) { b ->
            val nextState = !Prefs.playerMarginAnim(this)
            Prefs.setPlayerMarginAnim(this, nextState)
            b.text = if (nextState) "\u2728 Margin: ON" else "\u2728 Margin: OFF"
            marginAtomic.applyPrefs()
            showCenterToast(if (nextState) "Margin Animation: ON" else "Margin Animation: OFF (Clean Black)")
        }
        ex.addView(marginBtn)

        ex.addView(ib("100% Zoom", 13f, ic) {
            zoomScale = 1.0f
            surface.scaleX = 1.0f
            surface.scaleY = 1.0f
            marginAtomic.invalidate()
            showCenterToast("Zoom Reset: 100%")
        })

        ex.addView(ib("Audio", 13f, ic) { Dialogs.audioTracks(this) })
        ex.addView(ib("Sub", 13f, ic) { Dialogs.subTracks(this) })
        ex.addView(ib("EQ", 13f, ic) { Dialogs.eq(this) })
        hs.addView(ex)
        bottomBar.addView(hs, LinearLayout.LayoutParams(MP, WC))

        uiOverlay.addView(bottomBar, FrameLayout.LayoutParams(MP, WC, Gravity.BOTTOM))
        root.addView(uiOverlay, FrameLayout.LayoutParams(MP, MP))

        // Center Status Toast
        centerToast = TextView(this)
        centerToast.textSize = 16f
        centerToast.setTextColor(Color.WHITE)
        centerToast.setTypeface(null, Typeface.BOLD)
        centerToast.setPadding(dp(18), dp(10), dp(18), dp(10))
        val toastBg = GradientDrawable()
        toastBg.setColor(0xCC000000.toInt())
        toastBg.cornerRadius = dp(12).toFloat()
        centerToast.background = toastBg
        centerToast.visibility = View.GONE
        root.addView(centerToast, FrameLayout.LayoutParams(WC, WC, Gravity.CENTER))

        if (Prefs.rgbBorder(this)) root.addView(RgbBorderView(this), FrameLayout.LayoutParams(MP, MP))
        setContentView(root)
        hideSystemBars()

        Engine.listener = lis
        if (!cont) {
            Engine.setQueue(this, uris, names, index, true)
        }
        applySmoothAvSyncSettings()
        onTrackLoaded()
        resetHideTimer()
    }

    // Ensures smooth Audio-Video sync at 1.5x, 2x, 2.5x, 3x, 4x, 6x without stuttering
    private fun applySmoothAvSyncSettings() {
        try {
            val p = Engine.mp ?: return
            p.media?.let { m ->
                m.addOption(":audio-time-stretch")
                m.addOption(":avcodec-fast")
                m.addOption(":avcodec-skiploopfilter=4")
                m.addOption(":file-caching=1500")
                m.addOption(":network-caching=1500")
                m.addOption(":clock-jitter=0")
                m.addOption(":clock-synchro=0")
            }
        } catch (e: Exception) {
        }
    }

    private fun handleTouch(ev: MotionEvent): Boolean {
        // Always feed ScaleGestureDetector first
        scaleDetector.onTouchEvent(ev)

        val now = SystemClock.uptimeMillis()

        // Strictly isolate multi-touch / zooming from volume, brightness, and seeking
        if (ev.pointerCount > 1 || isZooming || scaleDetector.isInProgress || now < zoomCooldownUntil) {
            touchMode = 0
            if (ev.pointerCount > 1) zoomCooldownUntil = now + 220L
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                touchMode = 0
                startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val lp = window.attributes
                startBright = if (lp.screenBrightness > 0f) lp.screenBrightness else 0.5f
                startSeekPos = Engine.mp?.time ?: 0L
            }

            MotionEvent.ACTION_MOVE -> {
                if (locked) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                val slop = dp(18).toFloat()

                if (touchMode == 0) {
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) {
                        touchMode = 1 // Horizontal Seek
                    } else if (abs(dy) > slop && abs(dy) > abs(dx) * 1.3f) {
                        touchMode = if (downX < root.width * 0.5f) 3 else 2 // Left = Brightness, Right = Volume
                    }
                }

                when (touchMode) {
                    1 -> {
                        val len = Engine.mp?.length ?: 0L
                        if (len > 0L) {
                            val deltaMs = ((dx / root.width.coerceAtLeast(1)) * 90_000f).toLong()
                            val target = (startSeekPos + deltaMs).coerceIn(0L, len)
                            Engine.seekTo(target)
                            showCenterToast(fmt(target) + " / " + fmt(len))
                        }
                    }
                    2 -> {
                        val deltaV = ((-dy / (root.height.coerceAtLeast(1) * 0.65f)) * maxVol).roundToInt()
                        val newVol = (startVol + deltaV).coerceIn(0, maxVol)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                        val pct = ((newVol.toFloat() / maxVol) * 100).roundToInt()
                        showCenterToast("\uD83D\uDD0A Volume: $pct%")
                    }
                    3 -> {
                        val deltaB = -dy / (root.height.coerceAtLeast(1) * 0.65f)
                        val newB = (startBright + deltaB).coerceIn(0.05f, 1.0f)
                        val lp = window.attributes
                        lp.screenBrightness = newB
                        window.attributes = lp
                        val pct = (newB * 100).roundToInt()
                        showCenterToast("\u2600\uFE0F Brightness: $pct%")
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (touchMode == 0) {
                    val tapTime = SystemClock.uptimeMillis()
                    if (!locked && tapTime - lastTapTime < 280L && abs(ev.x - lastTapX) < dp(80)) {
                        val seekMs = Prefs.seekSec(this) * 1000L
                        if (ev.x < root.width * 0.4f) {
                            skipBy(-seekMs)
                        } else if (ev.x > root.width * 0.6f) {
                            skipBy(seekMs)
                        } else {
                            Engine.toggle(this)
                            updateProgress()
                        }
                        lastTapTime = 0L
                    } else {
                        lastTapTime = tapTime
                        lastTapX = ev.x
                        if (controlsVisible) hideControls() else showControls()
                    }
                }
                touchMode = 0
            }

            MotionEvent.ACTION_CANCEL -> {
                touchMode = 0
            }
        }
        return true
    }

    private fun skipBy(deltaMs: Long) {
        val p = Engine.mp ?: return
        val len = p.length
        if (len <= 0L) return
        val next = (p.time + deltaMs).coerceIn(0L, len)
        Engine.seekTo(next)
        showCenterToast((if (deltaMs > 0) "+" else "") + (deltaMs / 1000) + "s (" + fmt(next) + ")")
        updateProgress()
    }

    private fun showCenterToast(msg: String) {
        centerToast.text = msg
        centerToast.visibility = View.VISIBLE
        ui.removeCallbacks(hideToastRun)
        ui.postDelayed(hideToastRun, 900)
    }

    private fun toggleLock() {
        locked = !locked
        lockBtn.text = if (locked) "\uD83D\uDD12" else "\uD83D\uDD13"
        topBar.visibility = if (locked) View.GONE else View.VISIBLE
        bottomBar.visibility = if (locked) View.GONE else View.VISIBLE
        showCenterToast(if (locked) "Controls Locked" else "Controls Unlocked")
        resetHideTimer()
    }

    private fun showControls() {
        controlsVisible = true
        lockBtn.visibility = View.VISIBLE
        if (!locked) {
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
        }
        resetHideTimer()
    }

    private fun hideControls() {
        controlsVisible = false
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
        lockBtn.visibility = View.GONE
        hideSystemBars()
    }

    private fun resetHideTimer() {
        ui.removeCallbacks(hideControlsRun)
        if (controlsVisible) ui.postDelayed(hideControlsRun, 4000)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun toggleOrientation() {
        val cur = resources.configuration.orientation
        requestedOrientation = if (cur == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun cycleRatio() {
        ratioMode = (ratioMode + 1) % ratioNames.size
        ratioBtn.text = "Ratio: " + ratioNames[ratioMode]
        applyVideoLayout()
        showCenterToast("Aspect Ratio: " + ratioNames[ratioMode])
    }

    // Fits video cleanly into its native aspect ratio (16:9, 9:16, etc.) or user-selected size
    private fun applyVideoLayout() {
        val parentW = root.width
        val parentH = root.height
        if (parentW <= 0 || parentH <= 0) return

        val natW = if (videoW > 0) videoW else 16
        val natH = if (videoH > 0) videoH else 9
        val nativeRatio = natW.toFloat() / natH.toFloat()
        val screenRatio = parentW.toFloat() / parentH.toFloat()

        var targetW = parentW
        var targetH = parentH

        when (ratioMode) {
            0 -> { // Native Default Aspect Ratio (16:9 stays 16:9, 9:16 stays 9:16)
                if (nativeRatio > screenRatio) {
                    targetW = parentW
                    targetH = (parentW / nativeRatio).roundToInt()
                } else {
                    targetH = parentH
                    targetW = (parentH * nativeRatio).roundToInt()
                }
            }
            1 -> { // Fill Screen
                targetW = parentW
                targetH = parentH
            }
            2 -> { // 16:9
                val r = 16f / 9f
                if (r > screenRatio) {
                    targetW = parentW
                    targetH = (parentW / r).roundToInt()
                } else {
                    targetH = parentH
                    targetW = (parentH * r).roundToInt()
                }
            }
            3 -> { // 9:16
                val r = 9f / 16f
                if (r > screenRatio) {
                    targetW = parentW
                    targetH = (parentW / r).roundToInt()
                } else {
                    targetH = parentH
                    targetW = (parentH * r).roundToInt()
                }
            }
            4 -> { // 4:3
                val r = 4f / 3f
                if (r > screenRatio) {
                    targetW = parentW
                    targetH = (parentW / r).roundToInt()
                } else {
                    targetH = parentH
                    targetW = (parentH * r).roundToInt()
                }
            }
            5 -> { // Small 75% (Reveals Diamond Bonding Animation in surrounding black area)
                if (nativeRatio > screenRatio) {
                    targetW = (parentW * 0.75f).roundToInt()
                    targetH = (targetW / nativeRatio).roundToInt()
                } else {
                    targetH = (parentH * 0.75f).roundToInt()
                    targetW = (targetH * nativeRatio).roundToInt()
                }
            }
            6 -> { // Mini 55% (Reveals wide Diamond Bonding Animation margin around video)
                if (nativeRatio > screenRatio) {
                    targetW = (parentW * 0.55f).roundToInt()
                    targetH = (targetW / nativeRatio).roundToInt()
                } else {
                    targetH = (parentH * 0.55f).roundToInt()
                    targetW = (targetH * nativeRatio).roundToInt()
                }
            }
        }

        val lp = FrameLayout.LayoutParams(targetW.coerceAtLeast(100), targetH.coerceAtLeast(100), Gravity.CENTER)
        surface.layoutParams = lp
        try {
            Engine.mp?.vlcVout?.setWindowSize(targetW.coerceAtLeast(100), targetH.coerceAtLeast(100))
            Engine.mp?.aspectRatio = null
            Engine.mp?.scale = 0f
        } catch (e: Exception) {
        }
        surface.post { marginAtomic.invalidate() }
    }

    private fun speedDialog() {
        val labels = Array<CharSequence>(speeds.size) { i ->
            String.format("%.2fx", speeds[i]).replace(".00x", ".0x").replace("50x", "5x")
        }
        AlertDialog.Builder(this)
            .setTitle("Playback Speed (Smooth A/V Sync)")
            .setItems(labels) { _, which ->
                speedIdx = which
                val sp = speeds[which]
                applySmoothAvSyncSettings()
                Engine.mp?.rate = sp
                speedBtn.text = String.format("%.1fx", sp)
                showCenterToast("Speed: " + String.format("%.2fx", sp))
            }
            .show()
    }

    private fun bookmarksDialog() {
        val key = Engine.cur().toString()
        val list = Prefs.bms(this, key)
        val items = ArrayList<CharSequence>()
        items.add("\u2795 Add Bookmark at " + fmt(Engine.mp?.time ?: 0L))
        for (ms in list) items.add("\uD83D\uDD16 Jump to " + fmt(ms))
        if (list.isNotEmpty()) items.add("\uD83D\uDDD1 Clear All Bookmarks")

        val arr = items.toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("Video Bookmarks")
            .setItems(arr) { _, idx ->
                if (idx == 0) {
                    val now = Engine.mp?.time ?: 0L
                    Prefs.setBms(this, key, (list + now).distinct().sorted())
                    Toast.makeText(this, "Bookmark saved at " + fmt(now), Toast.LENGTH_SHORT).show()
                } else if (list.isNotEmpty() && idx == arr.size - 1) {
                    Prefs.setBms(this, key, emptyList())
                    Toast.makeText(this, "Bookmarks cleared", Toast.LENGTH_SHORT).show()
                } else {
                    val target = list.getOrNull(idx - 1) ?: return@setItems
                    Engine.seekTo(target)
                }
            }
            .show()
    }

    private fun onTrackLoaded() {
        if (!Engine.active) {
            finish()
            return
        }
        val curUri = Engine.items.getOrNull(Engine.idx)
        if (curUri != null) Prefs.setLastPlayed(this, curUri.toString())
        titleTv.text = Engine.title
        applySmoothAvSyncSettings()
        updateProgress()
    }

    private fun updateProgress() {
        val p = Engine.mp ?: return
        val len = p.length
        val t = p.time
        if (len > 0) {
            if (!userSeeking) {
                seek.max = len.toInt()
                seek.progress = t.toInt()
            }
            durTv.text = fmt(len)
        }
        if (!userSeeking) curTv.text = fmt(t)
        playBtn.text = if (p.isPlaying) "\u23F8" else "\u25B6"
    }

    private fun attachSurface() {
        val p = Engine.mp ?: return
        if (attached) return
        val vout = p.vlcVout
        vout.setVideoView(surface)
        vout.attachViews(this)
        attached = true
        root.post { applyVideoLayout() }
    }

    private fun detachSurface() {
        if (!attached) return
        try {
            Engine.mp?.vlcVout?.detachViews()
        } catch (e: Exception) {
        }
        attached = false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachSurface()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        try {
            Engine.mp?.vlcVout?.setWindowSize(width, height)
        } catch (e: Exception) {
        }
        marginAtomic.invalidate()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        detachSurface()
    }

    override fun onNewVideoLayout(
        vout: IVLCVout?,
        width: Int,
        height: Int,
        visibleWidth: Int,
        visibleHeight: Int,
        sarNum: Int,
        sarDen: Int
    ) {
        if (width > 0 && height > 0) {
            videoW = if (visibleWidth > 0) visibleWidth else width
            videoH = if (visibleHeight > 0) visibleHeight else height
            runOnUiThread { applyVideoLayout() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root.postDelayed({ applyVideoLayout() }, 80)
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val r = Rational(
                    (if (videoW > 0) videoW else 16).coerceIn(1, 239),
                    (if (videoH > 0) videoH else 9).coerceIn(1, 100)
                )
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(r).build())
            } catch (e: Exception) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        marginAtomic.applyPrefs()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        val curKey = Engine.cur().toString()
        val pos = Engine.mp?.time ?: 0L
        if (curKey.isNotEmpty() && pos > 0L) Prefs.setPos(this, curKey, pos)
    }

    override fun onDestroy() {
        super.onDestroy()
        detachSurface()
        ui.removeCallbacksAndMessages(null)
        if (Engine.listener === lis) Engine.listener = null
    }
}
EOF

# ==================== 7. Patch Engine.kt for Smooth High-Speed A/V Sync ====================
if [ -f "$PK/Engine.kt" ]; then
  python3 -c "
p = '$PK/Engine.kt'
with open(p, 'r') as f:
    c = f.read()

# Ensure audio-time-stretch is enabled on LibVLC or Media so 1.5x - 6.0x never drops audio sync
if '--audio-time-stretch' not in c and 'LibVLC(' in c:
    c = c.replace('LibVLC(c.applicationContext)', 'LibVLC(c.applicationContext, arrayListOf(\"--audio-time-stretch\", \"--avcodec-fast\", \"--avcodec-skiploopfilter=4\", \"--file-caching=1500\"))')
with open(p, 'w') as f:
    f.write(c)
"
fi

echo "=== Verification ==="
grep -c "" "$MF" "$PK/Prefs.kt" "$PK/AtomicView.kt" "$PK/MainActivity.kt" "$PK/AudioActivity.kt" "$PK/PlayerActivity.kt"

git add -A
git commit -m "RajPlayer: Complete implementation of margin atomic view, 6x A/V sync, zoom isolation, native aspect ratio, and last played highlight"
git push
echo "=== All files updated and pushed to GitHub successfully! ==="
