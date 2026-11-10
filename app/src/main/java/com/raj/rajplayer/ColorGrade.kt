package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

// Colour grading (brightness / contrast / saturation / warmth) + HDR boost.
// Grading ON = video TextureView me chalta hai; OFF = asli surface (native HDR passthrough)
object ColorGrade {
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

    // video layout ke TextureView par filter lagao / hatao
    fun apply(c: Context, root: View?) {
        val tv = findTexture(root) ?: return
        val paint = Paint()
        if (on(c)) paint.colorFilter = ColorMatrixColorFilter(matrix(bright(c), contrast(c), sat(c), warm(c)))
        tv.setLayerPaint(paint)
    }

    @Suppress("DEPRECATION")
    fun hdrSupported(a: Activity): Boolean {
        return try {
            a.windowManager.defaultDisplay.isHdr
        } catch (e: Exception) {
            false
        }
    }

    // HDR video + grading OFF: window ko HDR colour mode me daalo
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

    fun dialog(a: Activity, note: String, onMode: () -> Unit, onValues: () -> Unit) {
        val d = a.resources.displayMetrics.density
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())

        val noteTv = TextView(a)
        noteTv.text = note
        noteTv.setTextColor(Color.WHITE)
        noteTv.textSize = 12f
        box.addView(noteTv)

        val onBtn = Button(a)
        fun paintBtn() {
            onBtn.text = "Colour grading / HDR boost: " + (if (on(a)) "ON" else "OFF (native surface)")
        }
        paintBtn()
        onBtn.setOnClickListener {
            setOn(a, !on(a))
            paintBtn()
            onMode()
        }
        box.addView(onBtn)

        val titles = arrayOf("Brightness", "Contrast %", "Saturation %", "Warmth")
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

        // presets
        val ph = HorizontalScrollView(a)
        val pr = LinearLayout(a)
        for (k in presetNames.indices) {
            val b = Button(a)
            b.text = presetNames[k]
            b.setOnClickListener {
                val v = presetVals[k]
                for (i in 0..3) {
                    setValue(a, i, v[i])
                    bars[i].progress = v[i] - mins[i]
                    tvs[i].text = titles[i] + ": " + v[i]
                }
                if (k != 0 && !on(a)) {
                    setOn(a, true)
                    paintBtn()
                    onMode()
                }
                onValues()
            }
            pr.addView(b)
        }
        ph.addView(pr)
        box.addView(ph)

        for (i in 0..3) {
            val tv = TextView(a)
            tv.setTextColor(Color.WHITE)
            tv.textSize = 13f
            tv.text = titles[i] + ": " + cur(i)
            val sb = SeekBar(a)
            sb.max = maxs[i] - mins[i]
            sb.progress = cur(i) - mins[i]
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
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            bars.add(sb)
            tvs.add(tv)
            box.addView(tv)
            box.addView(sb)
        }

        val sv = ScrollView(a)
        sv.addView(box)
        val dlg = AlertDialog.Builder(a)
            .setTitle("Colour Grading / HDR")
            .setView(sv)
            .setPositiveButton("Done", null)
            .create()
        dlg.show()
        val w = dlg.window
        if (w != null) {
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0f)
            w.setBackgroundDrawable(ColorDrawable(0xEE10151C.toInt()))
        }
    }
}
