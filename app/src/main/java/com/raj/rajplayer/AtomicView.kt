package com.raj.rajplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View

class AtomicView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private var hue = 0f

    data class Node(var x: Float, var y: Float, var vx: Float, var vy: Float)
    private val points = ArrayList<Node>()

    init {
        for (i in 0..7) {
            points.add(Node(Math.random().toFloat() * 800f, Math.random().toFloat() * 800f, (Math.random().toFloat() - 0.5f) * 2f, (Math.random().toFloat() - 0.5f) * 2f))
        }
        handler.post(object : Runnable {
            override fun run() {
                val mode = Prefs.themeMode(context)
                if (mode != "none" && Prefs.atomicAnim(context)) {
                    val speed = Prefs.animSpeed(context)
                    for (p in points) {
                        p.x += p.vx * speed
                        p.y += p.vy * speed
                        if (p.x < 0 || p.x > width) p.vx *= -1
                        if (p.y < 0 || p.y > height) p.vy *= -1
                    }
                    invalidate()
                }
                handler.postDelayed(this, 40)
            }
        })
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mode = Prefs.themeMode(context)
        if (mode == "none" || !Prefs.atomicAnim(context)) return

        hue = (hue + 3f) % 360f
        val color = when(mode) {
            "pink" -> Color.parseColor("#FF4081")
            "dark" -> Color.parseColor("#00E676")
            "light" -> Color.parseColor("#2979FF")
            "gradient" -> Color.parseColor("#E040FB")
            else -> Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        }

        paint.strokeWidth = 2f
        paint.style = Paint.Style.STROKE
        paint.color = color

        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val p1 = points[i]
                val p2 = points[j]
                val dist = java.lang.Math.hypot((p1.x - p2.x).toDouble(), (p1.y - p2.y).toDouble())
                if (dist < 300) {
                    paint.alpha = ((1.0 - (dist / 300.0)) * 180).toInt()
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
                }
            }
        }

        paint.style = Paint.Style.FILL
        paint.color = color
        for (p in points) {
            canvas.drawCircle(p.x, p.y, 6f, paint)
        }
    }
}
