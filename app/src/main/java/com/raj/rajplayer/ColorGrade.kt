package com.raj.rajplayer

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.WeakHashMap

object ColorGrade {
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var cachedFilterKey: String? = null
    private var cachedColorFilter: ColorMatrixColorFilter? = null
    private val savedBrightness = WeakHashMap<Activity, Float>()

    private fun p(c: Context) = c.getSharedPreferences("rajplayer_ultra", Context.MODE_PRIVATE)

    fun on(c: Context): Boolean = p(c).getBoolean("cg_on", false)
    fun setOn(c: Context, v: Boolean) { p(c).edit().putBoolean("cg_on", v).apply() }
    fun hdrPeak(c: Context): Boolean = p(c).getBoolean("cg_hdr_peak", false)
    fun setHdrPeak(c: Context, v: Boolean) { p(c).edit().putBoolean("cg_hdr_peak", v).apply() }

    fun hdrLevel(c: Context): Int = p(c).getInt("cg_hdr", 0).coerceIn(0, 100)
    fun bright(c: Context): Int = p(c).getInt("cg_b", 0).coerceIn(-100, 100)
    fun contrast(c: Context): Int = p(c).getInt("cg_c", 100).coerceIn(50, 200)
    fun sat(c: Context): Int = p(c).getInt("cg_s", 100).coerceIn(0, 200)
    fun warm(c: Context): Int = p(c).getInt("cg_w", 0).coerceIn(-50, 50)
    fun shadow(c: Context): Int = p(c).getInt("cg_sh", 0).coerceIn(-30, 50)

    fun setValue(c: Context, idx: Int, v: Int) {
        val key = when (idx) {
            0 -> "cg_hdr"; 1 -> "cg_b"; 2 -> "cg_c"; 3 -> "cg_s"; 4 -> "cg_w"; else -> "cg_sh"
        }
        p(c).edit().putInt(key, v).apply()
    }

    fun matrix(b: Int, con: Int, sat: Int, warm: Int): ColorMatrix = matrixPro(0, b, con, sat, warm, 0)

    fun matrixPro(hdr: Int, b: Int, con: Int, sat: Int, warm: Int, sh: Int): ColorMatrix {
        val h = hdr.coerceIn(0, 100) / 100f
        val m = ColorMatrix()
        val effSat = ((sat / 100f) * (1f + h * 0.24f)).coerceIn(0f, 2.5f)
        m.setSaturation(effSat)
        val effCon = ((con / 100f) * (1f + h * 0.22f)).coerceIn(0.4f, 2.3f)
        val t = (-0.46f * effCon + 0.46f) * 255f + (b * 2.1f) + (sh * 0.85f) + (h * 10f)
        m.postConcat(ColorMatrix(floatArrayOf(
            effCon, 0f, 0f, 0f, t,
            0f, effCon, 0f, 0f, t,
            0f, 0f, effCon, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )))
        if (warm != 0 || h > 0f) {
            val w = (warm / 100f) * 0.28f
            val rGain = 1f + w + (h * 0.015f)
            val gGain = 1f + (h * 0.008f)
            val bGain = 1f - w + (h * 0.02f)
            m.postConcat(ColorMatrix(floatArrayOf(
                rGain, 0f, 0f, 0f, 0f,
                0f, gGain, 0f, 0f, 0f,
                0f, 0f, bGain, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        return m
    }

    private fun findTexture(v: View?): TextureView? {
        if (v == null) return null
        if (v is TextureView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findTexture(v.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }

    fun apply(c: Context, root: View?) {
        val tv = findTexture(root) ?: return
        if (on(c)) {
            val h = hdrLevel(c); val b = bright(c); val con = contrast(c)
            val s = sat(c); val w = warm(c); val sh = shadow(c)
            val key = "$h:$b:$con:$s:$w:$sh"
            if (cachedFilterKey != key || cachedColorFilter == null) {
                cachedColorFilter = ColorMatrixColorFilter(matrixPro(h, b, con, s, w, sh))
                cachedFilterKey = key
            }
            layerPaint.colorFilter = cachedColorFilter
        } else {
            layerPaint.colorFilter = null
        }
        tv.setLayerPaint(layerPaint)
    }

    @Suppress("DEPRECATION")
    fun hdrSupported(a: Activity): Boolean = try {
        val d = if (Build.VERSION.SDK_INT >= 30) a.display else a.windowManager.defaultDisplay
        d?.isHdr == true
    } catch (_: Exception) { false }

    @Suppress("DEPRECATION")
    private fun wideColorSupported(a: Activity): Boolean = try {
        val d = if (Build.VERSION.SDK_INT >= 30) a.display else a.windowManager.defaultDisplay
        Build.VERSION.SDK_INT >= 26 && d?.isWideColorGamut == true
    } catch (_: Exception) { false }

    private fun setHdrHeadroomSafe(w: Window, headroom: Float) {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            Window::class.java.getMethod("setDesiredHdrHeadroom", Float::class.javaPrimitiveType).invoke(w, headroom)
        } catch (_: Throwable) {}
    }

    fun hdrWindow(a: Activity, enable: Boolean) {
        try {
            val w = a.window
            val peak = hdrPeak(a)
            if (Build.VERSION.SDK_INT >= 26) {
                w.colorMode = when {
                    enable && hdrSupported(a) -> ActivityInfo.COLOR_MODE_HDR
                    (enable || on(a)) && wideColorSupported(a) -> ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
                    else -> ActivityInfo.COLOR_MODE_DEFAULT
                }
            }
            setHdrHeadroomSafe(w, if (enable) (if (peak) 5.0f else 3.0f) else 0f)
            val lp = w.attributes
            if (enable && peak) {
                if (!savedBrightness.containsKey(a)) savedBrightness[a] = lp.screenBrightness
                if (lp.screenBrightness < 0.92f) { lp.screenBrightness = 0.92f; w.attributes = lp }
            } else if (savedBrightness.containsKey(a)) {
                val prev = savedBrightness.remove(a) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                if (lp.screenBrightness != prev) { lp.screenBrightness = prev; w.attributes = lp }
            }
        } catch (_: Exception) {}
    }

    private val presetNames = arrayOf("Default", "Natural HDR", "Vivid HDR", "Ultra HDR+", "Cinema HDR", "Night HDR", "Warm", "Cool", "B&W")
    private val presetVals = arrayOf(
        intArrayOf(0, 0, 100, 100, 0, 0),
        intArrayOf(45, 2, 108, 112, 4, 8),
        intArrayOf(68, 4, 114, 122, 4, 10),
        intArrayOf(88, 6, 118, 125, 6, 15),
        intArrayOf(52, -2, 116, 98, 10, 6),
        intArrayOf(35, 8, 104, 108, 14, 22),
        intArrayOf(15, 0, 105, 110, 26, 4),
        intArrayOf(15, 0, 106, 106, -24, 2),
        intArrayOf(20, 0, 118, 0, 0, 4)
    )

    private fun matchingPresetIndex(c: Context): Int {
        val h = hdrLevel(c); val b = bright(c); val con = contrast(c)
        val s = sat(c); val w = warm(c); val sh = shadow(c)
        for (k in presetVals.indices) {
            val v = presetVals[k]
            if (v[0] == h && v[1] == b && v[2] == con && v[3] == s && v[4] == w && v[5] == sh) return k
        }
        return -1
    }

    fun dialog(a: Activity, note: String, onMode: () -> Unit, onValues: () -> Unit) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        box.addView(TextView(a).apply { text = note; setTextColor(0xFFCCCCCC.toInt()); textSize = 12f })

        val onBtn = Button(a)
        val peakBtn = Button(a)
        fun paintToggles() {
            val active = on(a)
            onBtn.text = "HDR ENGINE / COLOUR GRADING: " + (if (active) "ON" else "OFF (NATIVE HDR)")
            onBtn.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (active) 0xFF1B3A2C.toInt() else 0xFF2B313B.toInt())
                setStroke(dp(1), if (active) 0xFF00E676.toInt() else 0xFF555E6B.toInt())
            }
            onBtn.setTextColor(Color.WHITE)

            val pk = hdrPeak(a)
            peakBtn.text = "HDR PEAK BRIGHTNESS & WIDE GAMUT: " + (if (pk) "ON" else "OFF")
            peakBtn.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (pk) 0xFF3A2810.toInt() else 0xFF252A33.toInt())
                setStroke(dp(1), if (pk) 0xFFFFB300.toInt() else 0xFF4F5765.toInt())
            }
            peakBtn.setTextColor(Color.WHITE)
        }
        paintToggles()
        box.addView(onBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, dp(4)) })
        box.addView(peakBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(8)) })

        val activeLabel = TextView(a).apply { textSize = 13f; setTypeface(null, Typeface.BOLD); setPadding(0, dp(2), 0, dp(6)) }
        box.addView(activeLabel)

        val titles = arrayOf("HDR Intensity %", "Brightness", "Contrast %", "Saturation %", "Warmth", "Shadow Lift")
        val sliderColors = intArrayOf(0xFF00E676.toInt(), 0xFFFFB300.toInt(), 0xFF00E5FF.toInt(), 0xFFFF2E93.toInt(), 0xFFFF6D00.toInt(), 0xFFB388FF.toInt())
        val mins = intArrayOf(0, -100, 50, 0, -50, -30)
        val maxs = intArrayOf(100, 100, 200, 200, 50, 50)
        val bars = ArrayList<SeekBar>()
        val tvs = ArrayList<TextView>()
        fun cur(i: Int): Int = when (i) { 0 -> hdrLevel(a); 1 -> bright(a); 2 -> contrast(a); 3 -> sat(a); 4 -> warm(a); else -> shadow(a) }

        val ph = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
        val pr = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        ph.addView(pr); box.addView(ph)

        var selectedChipView: Button? = null
        var currentRgbColor = Color.CYAN
        fun styleSelectedChip(btn: Button, color: Int) {
            btn.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(46, Color.red(color), Color.green(color), Color.blue(color)))
                setStroke(dp(2), color)
            }
            btn.setTextColor(color)
        }
        fun styleNormalChip(btn: Button) {
            btn.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFF2A313C.toInt())
                setStroke(dp(1), 0xFF455060.toInt())
            }
            btn.setTextColor(Color.WHITE)
        }

        fun rebuildPresets() {
            pr.removeAllViews(); selectedChipView = null
            val match = matchingPresetIndex(a)
            activeLabel.text = "Preset: " + (if (match >= 0) presetNames[match] else "Custom")
            activeLabel.setTextColor(currentRgbColor)
            val ordered = ArrayList<Pair<Int, String>>()
            if (match == -1) {
                ordered.add(-1 to "Custom")
                for (k in presetNames.indices) ordered.add(k to presetNames[k])
            } else {
                ordered.add(match to presetNames[match])
                for (k in presetNames.indices) if (k != match) ordered.add(k to presetNames[k])
            }
            for ((idx, name) in ordered) {
                val isSelected = (idx == match)
                val b = Button(a).apply {
                    text = if (isSelected) "★ $name" else name
                    textSize = 12f; isAllCaps = false
                    setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    minWidth = 0; minimumWidth = 0; minHeight = dp(38); minimumHeight = dp(38)
                }
                if (isSelected) { styleSelectedChip(b, currentRgbColor); selectedChipView = b } else styleNormalChip(b)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), dp(8), dp(8)) }
                if (idx >= 0) {
                    b.setOnClickListener {
                        val v = presetVals[idx]
                        for (i in 0..5) { setValue(a, i, v[i]); bars[i].progress = v[i] - mins[i]; tvs[i].text = titles[i] + ": " + v[i] }
                        if (idx == 0) {
                            setHdrPeak(a, false)
                            if (on(a)) { setOn(a, false); paintToggles(); onMode() } else paintToggles()
                        } else {
                            if (idx == 3) setHdrPeak(a, true)
                            if (!on(a)) { setOn(a, true); paintToggles(); onMode() } else paintToggles()
                        }
                        onValues(); rebuildPresets(); ph.smoothScrollTo(0, 0)
                    }
                }
                pr.addView(b, lp)
            }
            ph.scrollTo(0, 0)
        }

        onBtn.setOnClickListener { setOn(a, !on(a)); paintToggles(); onMode(); rebuildPresets() }
        peakBtn.setOnClickListener { setHdrPeak(a, !hdrPeak(a)); paintToggles(); onValues() }

        for (i in 0..5) {
            val col = sliderColors[i]
            val tv = TextView(a).apply { setTextColor(col); textSize = 13f; setTypeface(null, Typeface.BOLD); text = titles[i] + ": " + cur(i); setPadding(0, dp(6), 0, dp(2)) }
            val sb = SeekBar(a).apply {
                max = maxs[i] - mins[i]; progress = cur(i) - mins[i]
                progressTintList = ColorStateList.valueOf(col); thumbTintList = ColorStateList.valueOf(col)
                progressBackgroundTintList = ColorStateList.valueOf(0xFF3A424E.toInt())
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = p + mins[i]; tv.text = titles[i] + ": " + v; setValue(a, i, v)
                    if (!on(a)) { setOn(a, true); paintToggles(); onMode() }
                    onValues(); rebuildPresets()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            bars.add(sb); tvs.add(tv); box.addView(tv); box.addView(sb)
        }

        rebuildPresets()
        val rgbAnimator = ValueAnimator.ofArgb(Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED).apply {
            duration = 3600L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { anim -> val c = anim.animatedValue as Int; currentRgbColor = c; activeLabel.setTextColor(c); selectedChipView?.let { styleSelectedChip(it, c) } }
            start()
        }
        val dlg = AlertDialog.Builder(a).setTitle("HDR & Colour Grading").setView(ScrollView(a).apply { addView(box) })
            .setNeutralButton("Reset") { _, _ ->
                for (i in 0..5) setValue(a, i, presetVals[0][i])
                setHdrPeak(a, false)
                val wasOn = on(a); setOn(a, false)
                if (wasOn) onMode()
                onValues()
            }
            .setPositiveButton("Done", null).create()
        dlg.setOnDismissListener { rgbAnimator.cancel() }
        dlg.show()
        dlg.window?.let { w -> w.setGravity(Gravity.BOTTOM); w.setDimAmount(0f); w.setBackgroundDrawable(ColorDrawable(0xEE10151C.toInt())) }
    }
}
