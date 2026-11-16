package com.raj.rajplayer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView

class GearMotion(private val view: TextView) {
    private var rotationAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var stopped = true
    private var generation = 0

    fun start() {
        stopped = false
        startSlowRotation()
        startRgb()
        startPulse()
    }

    fun tap() {
        if (stopped) return

        generation++
        val token = generation
        rotationAnimator?.cancel()

        val startAngle = view.rotation
        rotationAnimator = ValueAnimator.ofFloat(startAngle, startAngle + 360f).apply {
            duration = 650L
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!stopped && token == generation) {
                    view.rotation = it.animatedValue as Float
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!stopped && token == generation) startSlowRotation()
                }
            })
            start()
        }
    }

    private fun startSlowRotation() {
        generation++
        val token = generation
        rotationAnimator?.cancel()

        val startAngle = view.rotation
        rotationAnimator = ValueAnimator.ofFloat(startAngle, startAngle + 360f).apply {
            duration = 5000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!stopped && token == generation) {
                    view.rotation = it.animatedValue as Float
                }
            }
            start()
        }
    }

    private fun startRgb() {
        val density = view.resources.displayMetrics.density
        colorAnimator = ValueAnimator.ofArgb(
            Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN,
            Color.GREEN, Color.YELLOW, Color.RED
        ).apply {
            duration = 4200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val c = it.animatedValue as Int
                view.setTextColor(c)
                view.setShadowLayer(7f * density, 0f, 0f, c)
            }
            start()
        }
    }

    private fun startPulse() {
        pulseAnimator = ValueAnimator.ofFloat(0.86f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                if (!stopped) view.alpha = it.animatedValue as Float
            }
            start()
        }
    }

    fun stop() {
        stopped = true
        generation++

        rotationAnimator?.cancel()
        rotationAnimator = null
        colorAnimator?.cancel()
        colorAnimator = null
        pulseAnimator?.cancel()
        pulseAnimator = null

        view.alpha = 1f
        view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }
}
