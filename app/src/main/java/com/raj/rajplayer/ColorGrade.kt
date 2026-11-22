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
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

// Colour grading (brightness / contrast / saturation / warmth) + HDR boost.
// Grading ON = video TextureView me chalta hai; OFF = asli surface (native HDR passthrough)
object ColorGrade {
    private val layerPaint = Paint()
    private var cachedFilterKey: String? = null
    private var cachedColorFilter: ColorMatrixColorFilter? = null

    private fun p(c: Context) = c.getSharedPreferences("rajplayer_ultra", Context.MODE_PRIVATE)

    fun on(c: Context): Boolean = p(c).getBoolean("cg_on", false)
    fun setOn(c: Context, v: Boolean) { p(c).edit().putBoolean("cg_on", v).apply() }
    fun bright(c: Context): Int = p(c).getInt("cg_b", 0)
    fun contrast(c: Context): Int = p(c).getInt("cg_c", 100)
    fun sat(c: Context): Int = p(c).getInt("cg_s", 100)
    fun warm(c: Context): Int = p(c).getInt("cg_w", 0)

    fun setValue(c: Context, idx: Int, v: Int) {
        val key = when (idx) {
            0 -> "cg_b"
            1 -> "cg_c"
            2 -> "cg_s"
            else -> "cg_w"
        }
        p(c).edit().putInt(key, v).apply()
    }

    fun matrix(b: Int, con: Int, sat: Int, warm: Int): ColorMatrix {
        val m = ColorMatrix()
        m.setSaturation(sat / 100f)
        val cs = con / 100f
        val t = (-0.5f * cs + 0.5f) * 255f + b * 2.55f
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    cs, 0f, 0f, 0f, t,
                    0f, cs, 0f, 0f, t,
                    0f, 0f, cs, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        if (warm != 0) {
            val w = warm / 100f * 0.3f
            m.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f + w, 0f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1f - w, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
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
            val b = bright(c)
            val con = contrast(c)
            val saturation = sat(c)
            val w = warm(c)
            val key = "$b:$con:$saturation:$w"

            if (cachedFilterKey != key || cachedColorFilter == null) {
                cachedColorFilter = ColorMatrixColorFilter(
                    matrix(b, con, saturation, w)
                )
                cachedFilterKey = key
            }
            layerPaint.colorFilter = cachedColorFilter
        } else {
            layerPaint.colorFilter = null
        }

        tv.setLayerPaint(layerPaint)
    }

    @Suppress("DEPRECATION")
    fun hdrSupported(a: Activity): Boolean {
        return try {
            a.windowManager.defaultDisplay.isHdr
        } catch (e: Exception) {
            false
        }
    }

    fun hdrWindow(a: Activity, enable: Boolean) {
        try {
            a.window.setColorMode(if (enable) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT)
        } catch (e: Exception) {
        }
    }

    private val presetNames = arrayOf("Default", "Vivid", "Cinematic", "Warm", "Cool", "B&W", "HDR Boost")
    private val presetVals = arrayOf(
        intArrayOf(0, 100, 100, 0),
        intArrayOf(5, 115, 135, 0),
        intArrayOf(-4, 115, 88, -10),
        intArrayOf(0, 105, 110, 25),
        intArrayOf(0, 105, 105, -25),
        intArrayOf(0, 115, 0, 0),
        intArrayOf(4, 125, 125, 5)
    )

    private fun matchingPresetIndex(c: Context): Int {
        val b = bright(c)
        val con = contrast(c)
        val s = sat(c)
        val w = warm(c)
        for (k in presetVals.indices) {
            val v = presetVals[k]
            if (v[0] == b && v[1] == con && v[2] == s && v[3] == w) return k
        }
        return -1 // Custom
    }

    fun dialog(a: Activity, note: String, onMode: () -> Unit, onValues: () -> Unit) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), dp(8))

        val noteTv = TextView(a)
        noteTv.text = note
        noteTv.setTextColor(0xFFCCCCCC.toInt())
        noteTv.textSize = 12f
        box.addView(noteTv)

        val onBtn = Button(a)
        fun paintBtn() {
            val active = on(a)
            onBtn.text = "COLOUR GRADING / HDR BOOST: " + (if (active) "ON" else "OFF (NATIVE SURFACE)")
            val bg = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (active) 0xFF1F3B2C.toInt() else 0xFF2B313B.toInt())
                setStroke(dp(1), if (active) 0xFF00E676.toInt() else 0xFF555E6B.toInt())
            }
            onBtn.background = bg
            onBtn.setTextColor(Color.WHITE)
        }
        val onLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        onLp.setMargins(0, dp(8), 0, dp(8))
        paintBtn()
        box.addView(onBtn, onLp)

        val activeLabel = TextView(a).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(6))
        }
        box.addView(activeLabel)

        val titles = arrayOf("Brightness", "Contrast %", "Saturation %", "Warmth")
        val sliderColors = intArrayOf(
            0xFFFFB300.toInt(), // Brightness: Solid Gold
            0xFF00E5FF.toInt(), // Contrast: Solid Cyan
            0xFFFF2E93.toInt(), // Saturation: Solid Vivid Pink
            0xFFFF6D00.toInt()  // Warmth: Solid Orange
        )
        val mins = intArrayOf(-100, 50, 0, -50)
        val maxs = intArrayOf(100, 200, 200, 50)
        val bars = ArrayList<SeekBar>()
        val tvs = ArrayList<TextView>()

        fun cur(i: Int): Int = when (i) {
            0 -> bright(a)
            1 -> contrast(a)
            2 -> sat(a)
            else -> warm(a)
        }

        val ph = HorizontalScrollView(a)
        ph.isHorizontalScrollBarEnabled = false
        val pr = LinearLayout(a)
        pr.orientation = LinearLayout.HORIZONTAL
        ph.addView(pr)
        box.addView(ph)

        var selectedChipView: Button? = null
        var currentRgbColor = Color.CYAN

        fun styleSelectedChip(btn: Button, color: Int) {
            val gd = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(46, Color.red(color), Color.green(color), Color.blue(color)))
                setStroke(dp(2), color)
            }
            btn.background = gd
            btn.setTextColor(color)
        }

        fun styleNormalChip(btn: Button) {
            val gd = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFF2A313C.toInt())
                setStroke(dp(1), 0xFF455060.toInt())
            }
            btn.background = gd
            btn.setTextColor(Color.WHITE)
        }

        fun rebuildPresets() {
            pr.removeAllViews()
            selectedChipView = null
            val match = matchingPresetIndex(a)
            val selectedName = if (match >= 0) presetNames[match] else "Custom"
            activeLabel.text = "Selected: $selectedName"
            activeLabel.setTextColor(currentRgbColor)

            // Build ordered list: selected preset (or Custom) ALWAYS first!
            val ordered = ArrayList<Pair<Int, String>>()
            if (match == -1) {
                ordered.add(-1 to "Custom")
                for (k in presetNames.indices) ordered.add(k to presetNames[k])
            } else {
                ordered.add(match to presetNames[match])
                for (k in presetNames.indices) {
                    if (k != match) ordered.add(k to presetNames[k])
                }
            }

            for ((idx, name) in ordered) {
                val isSelected = (idx == match)
                val b = Button(a).apply {
                    text = if (isSelected) "★ $name" else name
                    textSize = 12f
                    isAllCaps = false
                    setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = dp(38)
                    minimumHeight = dp(38)
                }
                if (isSelected) {
                    styleSelectedChip(b, currentRgbColor)
                    selectedChipView = b
                } else {
                    styleNormalChip(b)
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, dp(4), dp(8), dp(8))
                if (idx >= 0) {
                    b.setOnClickListener {
                        val v = presetVals[idx]
                        for (i in 0..3) {
                            setValue(a, i, v[i])
                            bars[i].progress = v[i] - mins[i]
                            tvs[i].text = titles[i] + ": " + v[i]
                        }
                        if (idx != 0 && !on(a)) {
                            setOn(a, true)
                            paintBtn()
                            onMode()
                        }
                        onValues()
                        rebuildPresets()
                        ph.smoothScrollTo(0, 0)
                    }
                }
                pr.addView(b, lp)
            }
            ph.scrollTo(0, 0)
        }

        onBtn.setOnClickListener {
            setOn(a, !on(a))
            paintBtn()
            onMode()
            rebuildPresets()
        }

        for (i in 0..3) {
            val col = sliderColors[i]
            val tv = TextView(a).apply {
                setTextColor(col)
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                text = titles[i] + ": " + cur(i)
                setPadding(0, dp(6), 0, dp(2))
            }
            val sb = SeekBar(a).apply {
                max = maxs[i] - mins[i]
                progress = cur(i) - mins[i]
                progressTintList = ColorStateList.valueOf(col)
                thumbTintList = ColorStateList.valueOf(col)
                progressBackgroundTintList = ColorStateList.valueOf(0xFF3A424E.toInt())
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = p + mins[i]
                    tv.text = titles[i] + ": " + v
                    setValue(a, i, v)
                    if (!on(a)) {
                        setOn(a, true)
                        paintBtn()
                        onMode()
                    }
                    onValues()
                    rebuildPresets()
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            bars.add(sb)
            tvs.add(tv)
            box.addView(tv)
            box.addView(sb)
        }

        rebuildPresets()

        val rgbAnimator = ValueAnimator.ofArgb(
            Color.RED, Color.MAGENTA, Color.BLUE,
            Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED
        ).apply {
            duration = 3600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val c = anim.animatedValue as Int
                currentRgbColor = c
                activeLabel.setTextColor(c)
                selectedChipView?.let { styleSelectedChip(it, c) }
            }
            start()
        }

        val sv = ScrollView(a)
        sv.addView(box)
        val dlg = AlertDialog.Builder(a)
            .setTitle("Colour Grading / HDR")
            .setView(sv)
            .setPositiveButton("Done", null)
            .create()
        dlg.setOnDismissListener {
            rgbAnimator.cancel()
        }
        dlg.show()
        val w = dlg.window
        if (w != null) {
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0f)
            w.setBackgroundDrawable(ColorDrawable(0xEE10151C.toInt()))
        }
    }
}
