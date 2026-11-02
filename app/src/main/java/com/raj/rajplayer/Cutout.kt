package com.raj.rajplayer

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager

object Cutout {

    // Video ko camera/notch ke peeche tak poori screen me failao
    fun enable(a: Activity) {
        val w = a.window
        val lp = w.attributes
        lp.layoutInDisplayCutoutMode =
            if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        w.attributes = lp
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false)
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
    }

    // Buttons/title camera ke neeche na chhupein, video poora full rahe
    fun padControls(root: View, top: View, bottom: View, side: View) {
        val t0 = intArrayOf(top.paddingLeft, top.paddingTop, top.paddingRight, top.paddingBottom)
        val b0 = intArrayOf(bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight, bottom.paddingBottom)
        val s0 = intArrayOf(side.paddingLeft, side.paddingTop, side.paddingRight, side.paddingBottom)
        root.setOnApplyWindowInsetsListener { _, ins ->
            val c = ins.displayCutout
            val l = c?.safeInsetLeft ?: 0
            val t = c?.safeInsetTop ?: 0
            val r = c?.safeInsetRight ?: 0
            val b = c?.safeInsetBottom ?: 0
            top.setPadding(t0[0] + l, t0[1] + t, t0[2] + r, t0[3])
            bottom.setPadding(b0[0] + l, b0[1], b0[2] + r, b0[3] + b)
            side.setPadding(s0[0] + l, s0[1], s0[2], s0[3])
            ins
        }
        root.requestApplyInsets()
    }
}
