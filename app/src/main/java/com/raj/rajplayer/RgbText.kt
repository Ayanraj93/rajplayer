package com.raj.rajplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.view.Choreographer
import android.widget.TextView

// Chalta-phirta RGB rainbow text (RajPlayer Pro title)
class RgbTextView(ctx: Context) : TextView(ctx) {

    private val m = Matrix()
    private var sh: LinearGradient? = null
    private var period = 1f
    private var shift = 0f
    private var running = false
    private var solidMode = false
    private var last = 0L

    fun setSolidColor(color: Int) {
        solidMode = true
        sh = null
        paint.shader = null
        setTextColor(color)
        invalidate()
    }

    fun useRgbColors() {
        solidMode = false
        sh = null
        invalidate()
    }

    private val cols = intArrayOf(
        0xFFFF1744.toInt(), 0xFFFF9100.toInt(), 0xFFFFEA00.toInt(), 0xFF00E676.toInt(),
        0xFF00E5FF.toInt(), 0xFF3D5AFE.toInt(), 0xFFD500F9.toInt(), 0xFFFF1744.toInt()
    )

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (last == 0L) last = frameTimeNanos
            val dt = (frameTimeNanos - last) / 1_000_000_000f
            if (dt >= 0.032f) {
                last = frameTimeNanos
                shift = (shift + dt.coerceAtMost(0.1f) * 0.4f) % 1f
                invalidate()
            }
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
        if (solidMode) {
            paint.shader = null
            super.onDraw(canvas)
            return
        }
        var s = sh
        if (s == null) {
            period = paint.measureText(text.toString()).coerceAtLeast(1f)
            s = LinearGradient(0f, 0f, period, 0f, cols, null, Shader.TileMode.REPEAT)
            sh = s
        }
        m.setTranslate(shift * period, 0f)
        s.setLocalMatrix(m)
        paint.shader = s
        super.onDraw(canvas)
    }
}
