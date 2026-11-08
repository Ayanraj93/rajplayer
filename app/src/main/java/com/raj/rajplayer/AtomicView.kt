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
