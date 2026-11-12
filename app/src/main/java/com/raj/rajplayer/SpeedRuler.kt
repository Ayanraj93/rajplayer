package com.raj.rajplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

// PLAYit jaisa speed ruler: ungli se khicho, 0.05x ke step, 0.25x se 4x tak
class SpeedRulerView(ctx: Context) : View(ctx) {

    var minV = 0.25f
    var maxV = 4f
    var value = 1f
    var accent = Color.parseColor("#00E676")
    var onChange: ((Float) -> Unit)? = null

    private val d = ctx.resources.displayMetrics.density
    private val pxPerUnit = 150f * d
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG)
    private var raw = 1f
    private var lastSnap = 1f

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            raw = (raw + dx / pxPerUnit).coerceIn(minV, maxV)
            val s = Math.round(raw * 20f) / 20f
            if (s != lastSnap) {
                lastSnap = s
                value = s
                if (Math.round(s * 20f) % 5 == 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onChange?.invoke(s)
            }
            invalidate()
            return true
        }
    })

    init {
        tick.color = Color.WHITE
        tick.strokeWidth = 1.5f * d
        tick.strokeCap = Paint.Cap.ROUND
        txt.color = Color.WHITE
        txt.textSize = 11f * d
        txt.textAlign = Paint.Align.CENTER
        mark.style = Paint.Style.FILL
    }

    fun jumpTo(v: Float) {
        val c = v.coerceIn(minV, maxV)
        raw = c
        value = c
        lastSnap = c
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return gd.onTouchEvent(e)
    }

    private fun lbl(t: Float): String =
        String.format(Locale.US, "%.2f", t).trimEnd('0').trimEnd('.') + "x"

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val baseY = h * 0.66f
        val half = (w / 2f) / pxPerUnit
        val i0 = Math.floor(((raw - half) * 20f).toDouble()).toInt()
        val i1 = Math.ceil(((raw + half) * 20f).toDouble()).toInt()
        for (i in i0..i1) {
            val t = i / 20f
            if (t < minV - 0.001f || t > maxV + 0.001f) continue
            val x = cx + (t - raw) * pxPerUnit
            val len = if (i % 20 == 0) 0.48f * h else if (i % 5 == 0) 0.34f * h else 0.2f * h
            val fade = (1f - Math.abs(x - cx) / (w / 2f) * 0.75f).coerceIn(0.15f, 1f)
            tick.alpha = (255 * fade).toInt()
            canvas.drawLine(x, baseY, x, baseY - len, tick)
            if (i % 10 == 0) {
                txt.alpha = (255 * fade).toInt()
                canvas.drawText(lbl(t), x, h * 0.92f, txt)
            }
        }
        mark.color = accent
        canvas.drawRect(cx - 1.6f * d, h * 0.04f, cx + 1.6f * d, baseY, mark)
    }
}

// Speed panel: ruler + presets + smart auto-fix + decoder
object SpeedPanel {

    private fun lbl(v: Float): String =
        String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.') + "x"

    fun show(a: Activity, cur: Float, accent: Int, onRate: (Float) -> Unit, onDecoder: () -> Unit) {
        val d = a.resources.displayMetrics.density
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())

        val valTv = TextView(a)
        valTv.textSize = 22f
        valTv.setTextColor(Color.WHITE)
        valTv.gravity = Gravity.CENTER
        valTv.setTypeface(null, Typeface.BOLD)
        valTv.text = lbl(cur)
        box.addView(valTv)

        val ruler = SpeedRulerView(a)
        ruler.accent = accent
        ruler.jumpTo(cur)
        ruler.onChange = { v ->
            valTv.text = lbl(v)
            onRate(v)
        }
        box.addView(ruler, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (84 * d).toInt()))

        val hs = HorizontalScrollView(a)
        val row = LinearLayout(a)
        for (p in floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)) {
            val b = Button(a)
            b.text = lbl(p)
            b.setOnClickListener {
                ruler.jumpTo(p)
                valTv.text = lbl(p)
                onRate(p)
            }
            row.addView(b)
        }
        hs.addView(row)
        box.addView(hs)

        val autoBtn = Button(a)
        fun paintAuto() {
            autoBtn.text = "Frame-drop Auto-Fix: " + (if (Tuning.autoFix(a)) "ON" else "OFF")
        }
        paintAuto()
        autoBtn.setOnClickListener {
            Tuning.setAutoFix(a, !Tuning.autoFix(a))
            paintAuto()
        }
        box.addView(autoBtn)

        var dlgRef: AlertDialog? = null
        val decBtn = Button(a)
        decBtn.text = "Decoder: " + Tuning.decLabel(Tuning.decMode(a))
        decBtn.setOnClickListener {
            dlgRef?.dismiss()
            onDecoder()
        }
        box.addView(decBtn)

        val tip = TextView(a)
        tip.text = "Media3 uses Android MediaCodec and its playback clock. Auto-Fix monitors dropped frames and avoids disruptive decoder reloads or speed changes."
        tip.setTextColor(0xCCFFFFFF.toInt())
        tip.textSize = 11f
        box.addView(tip)

        val sv = ScrollView(a)
        sv.addView(box)
        val dlg = AlertDialog.Builder(a)
            .setTitle("Speed")
            .setView(sv)
            .setPositiveButton("Done", null)
            .create()
        dlgRef = dlg
        dlg.show()
        val w = dlg.window
        if (w != null) {
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0f)
            w.setBackgroundDrawable(ColorDrawable(0xEE10151C.toInt()))
        }
    }
}
